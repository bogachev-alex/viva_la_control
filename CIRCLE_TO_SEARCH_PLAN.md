# Circle to Search как TargetAction — план

**Короткий ответ: да, можно. Без root, без Xposed, без Shizuku.**
Ядро MiCTS — это ~30 строк рефлексии по системному сервису `voiceinteraction`.
Всё остальное в том репозитории — это Xposed-обвязка ради *способов вызова*
(долгое нажатие на навбар/Home на Xiaomi). У нас способ вызова уже есть — это
и есть перехватчик кнопки BlueLM/Shutter. Поэтому от MiCTS нам нужен ровно
один метод, а не архитектура.

Риск проекта лежит **не в коде**, а в окружении: Google-приложение должно быть
ассистентом по умолчанию, не должно быть заморожено vivo, и Circle to Search
не должен быть выключен серверным флагом Google для вашей модели. Поэтому
шаг 0 плана — разведка на живом устройстве до написания фичи.

---

## 1. Как MiCTS на самом деле запускает Circle to Search

Три пути (`TriggerService` в `config/XposedConfig.kt`):

| Путь | Требования | Доступно нам без root |
|---|---|---|
| **VIS** | Android 9–16, Google = ассистент по умолчанию | **Да** |
| **CSHelper** | Android 14 QPR3+, хук `getString(config_defaultContextualSearchKey)` в system_server | Только если ROM уже отдаёт непустое значение (см. проба) |
| **CSService** | Android 15+, `ContextualSearchManager.startContextualSearch()` | Нет — разрешение `ACCESS_CONTEXTUAL_SEARCH` подписное |

Нам нужен **VIS**. Весь его код — `triggerCircleToSearch()` в
`ui/activity/MainActivity.kt` у MiCTS:

1. `ServiceManager.getService("voiceinteraction")` → `IBinder`
2. `IVoiceInteractionManagerService$Stub.asInterface(binder)`
3. `showSessionFromSession(null, bundle, 7, "hyperOS_home")`
   - `flags = 7` = `SHOW_WITH_ASSIST(1) | SHOW_WITH_SCREENSHOT(2) | SHOW_SOURCE_ASSIST_GESTURE(4)`.
     Бит скриншота обязателен — без него Circle to Search нечего обводить.
   - `bundle`: `omni.entry_point: Int`, `invocation_time_ms: Long`.
     Ключ `omni.entry_point` читает сам Google-апп и открывает CtS-режим
     вместо обычного ассистента. `micts_trigger` — их внутренний маркер для
     собственных хуков, **нам он не нужен**.
   - `attributionTag` (4-й аргумент) появился в API 34; на VIS-пути это просто
     атрибуция, значение роли не играет. На API < 34 метод 3-аргументный.
4. Вызов идёт через `HiddenApiBypass.invoke(...)`, потому что
   `IVoiceInteractionManagerService` — скрытый класс (denylist с API 28).

Ключевой момент: `showSessionFromSession` в AOSP **не проверяет разрешение
вызывающего**, когда `token == null`. Именно поэтому обычное приложение может
поднять сессию ассистента. Это и есть вся «магия».

### Что делает Xposed-часть MiCTS и почему нам она не нужна

- `NavStubViewHooker`, `NavStubGestureEventManagerHooker`, `LongPressHomeHooker`,
  `NavBarEventHelperHooker`, `InvokeOmniHooker`, `NavBarActionsConfigHooker` —
  это *триггеры* для Xiaomi/Meizu. У нас триггер = `onKeyEvent` в
  `BlueLMInterceptorService`.
- `VIMSHooker` / `CSMSHooker` — подмена системных ресурсов, чтобы включить
  пути CSHelper/CSService. Требует root.
- Спуфинг `Build.MANUFACTURER/BRAND/MODEL/DEVICE` внутри процесса Google —
  обход серверного гейтинга CtS. **Требует root, аналога без root нет.**

### Лицензия

MiCTS — **GPL-3.0**. Механизм (рефлексия по AOSP-интерфейсу) не охраняется,
но копипаста их `triggerCircleToSearch` целиком потянет за собой GPL на весь
наш APK. Пишем свою реализацию от AOSP-сигнатуры, в своём стиле; в комментарии
ставим ссылку на MiCTS как на источник идеи. Ни одной строки оттуда не копируем.

---

## 2. Чем наш случай лучше, чем у MiCTS

MiCTS вынужден запускать прозрачную `MainActivity`, потому что его триггер —
иконка/тайл. Отсюда у них костыли: `default_delay`, `tile_delay`,
`async_trigger` — они ждут, пока их же активность уедет с экрана, иначе она
попадёт в скриншот CtS.

У нас триггер — `AccessibilityService`. Мы вызываем `showSessionFromSession`
**прямо из сервиса, не поднимая ни одной активности**:

- скриншот CtS = реальное приложение пользователя, а не наш оверлей;
- нет задержек и подбора delay;
- нет мигания краёв экрана от запуска активности;
- нет ограничений background activity launch — мы не стартуем активность.

---

## 3. Подводные камни именно на OriginOS

### 3.1. Ассистент по умолчанию

VIS-путь показывает сессию **текущего** `VoiceInteractionService`. На vivo это
Jovi/BlueLM. Пока Google не выбран ассистентом, вызов либо вернёт `false`, либо
поднимет Jovi.

Пользователю: `Настройки → Приложения → Приложения по умолчанию → Цифровой
помощник → Google`. На части китайских прошивок пункта нет — тогда через ADB:

```bash
adb shell settings put secure assistant "com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService"
```

```bash
adb shell settings put secure voice_interaction_service "com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService"
```

Побочный эффект, который надо честно показать в UI: после этого
`TargetAction.DEFAULT_ASSISTANT` и системный assist-жест тоже начнут открывать
Google, а не Jovi.

### 3.2. Серверный гейтинг Google

Если Google решил, что на вашей модели CtS не положен, в logcat будет
`Omni invocation failed: not enabled`. Обходов без root два, оба плохие:
GMS-Flags (флаг `45631784` у `com.google.android.apps.search.omnient.device`)
и спуфинг модели — оба требуют root/ADB-привилегий. **Это проверяется на
шаге 0 и определяет, имеет ли смысл вся затея.**

### 3.3. Заморозка Google-аппа

vivo агрессивно морозит фон. Симптом из README MiCTS: триггер «срабатывает», но
CtS-оверлей появляется, только когда вручную открываешь Google. Лечение —
автозапуск + «Без ограничений» в энергопотреблении для Google. Мы это не чиним
кодом, но должны написать в UI.

### 3.4. Гонка со сворачиванием BlueLM — самое важное для нас

Наш путь для кнопки BlueLM (`fireBlueLMAction`, `BlueLMInterceptorService.kt:587`)
сейчас такой: система поднимает Jovi → мы шлём `GLOBAL_ACTION_BACK` → ждём
`dismissDelayMs` → выполняем TargetAction. Для CtS это критично: скриншот
снимается **в момент** `showSessionFromSession`. Если оверлей Jovi ещё на
экране, обводить будем его.

Значит: `CIRCLE_TO_SEARCH` обязан выполняться **после** полного схлопывания
оверлея, и `OriginOs.defaultDismissDelayMs()` (100/200 мс) может оказаться
маловат. Закладываем отдельную настраиваемую задержку для этого действия.
Для Shutter-кнопки такой проблемы нет — там оверлея нет.

---

## 4. План работ

### Шаг 0 — разведка на устройстве (делать до кода)

Цель — за 15 минут понять, работает ли VIS-путь на этом аппарате.

1. Поставить свежий Google-апп, выбрать его ассистентом (3.1), снять
   ограничения фона (3.3).
2. Поставить релиз MiCTS с https://github.com/parallelcc/MiCTS/releases,
   открыть → должен появиться CtS.
3. Параллельно смотреть логи:

```bash
adb logcat -c && adb logcat | grep -iE "omni|contextualsearch|voiceinteraction|not enabled"
```

4. Снять две характеристики устройства — они определяют доступные пути:

```bash
adb shell dumpsys voiceinteraction | head -40
```

```bash
adb shell "getprop ro.build.version.sdk; getprop ro.vivo.os.build.display.id"
```

**Развилка:**
- MiCTS работает → идём дальше, наш код будет работать тем более (у нас нет их
  проблемы с активностью).
- `not enabled` → без root не победим. Тогда либо root + LSPosed + спуфинг
  модели, либо закрываем тему и оставляем `SPECIFIC_APP` → Google Lens как
  суррогат.

### Шаг 1 — зависимость

`gradle/libs.versions.toml`:
```toml
hiddenapibypass = "6.1"
# [libraries]
hiddenapibypass = { group = "org.lsposed.hiddenapibypass", name = "hiddenapibypass", version.ref = "hiddenapibypass" }
```
`app/build.gradle.kts`: `implementation(libs.hiddenapibypass)`.

У нас `minSdk = 23`: на API < 28 denylist ещё нет, обычная рефлексия работает —
ветвим по `Build.VERSION.SDK_INT`. `targetSdk = 37` означает самый строгий
denylist, так что bypass обязателен.

Инициализация один раз, в `BlueLMInterceptorService.onServiceConnected()`
(`:271`) — не на горячем пути клавиши:
`HiddenApiBypass.addHiddenApiExemptions("Lcom/android/internal/app/", "Landroid/os/ServiceManager")`.

### Шаг 2 — новый файл `engine/CircleToSearch.kt`

Единственный новый модуль. Чистые функции — отдельно от вызова, чтобы
покрыть тестами (как сделано в `OriginOs.kt`).

```kotlin
package com.example.bluelm_interceptor.engine

/**
 * Circle to Search через IVoiceInteractionManagerService.showSessionFromSession.
 * Метод не проверяет разрешение вызывающего при token == null, поэтому обычное
 * приложение может поднять сессию ассистента. Google-апп читает omni.entry_point
 * из sessionArgs и открывает CtS вместо обычного ассистента.
 * Идея пути подсмотрена в MiCTS (GPL-3.0); код здесь свой.
 */
object CircleToSearch {

    const val FLAG_SHOW_WITH_ASSIST = 1
    const val FLAG_SHOW_WITH_SCREENSHOT = 2
    const val FLAG_SHOW_SOURCE_ASSIST_GESTURE = 4
    const val SHOW_FLAGS = FLAG_SHOW_WITH_ASSIST or
        FLAG_SHOW_WITH_SCREENSHOT or
        FLAG_SHOW_SOURCE_ASSIST_GESTURE

    private const val KEY_ENTRY_POINT = "omni.entry_point"
    private const val KEY_INVOCATION_TIME = "invocation_time_ms"
    private const val DEFAULT_ENTRY_POINT = 1

    data class Readiness(
        val googleInstalled: Boolean,
        val googleIsAssistant: Boolean,
        val contextualSearchKey: String?,   // непустой ⇒ ROM умеет CSHelper сам
        val serviceAvailable: Boolean,
    ) {
        val usable: Boolean get() = serviceAvailable && googleInstalled &&
            (googleIsAssistant || !contextualSearchKey.isNullOrEmpty())
        val blocker: String? get() = when {
            !serviceAvailable -> "voiceinteraction service unavailable"
            !googleInstalled -> "Google app not installed"
            !googleIsAssistant && contextualSearchKey.isNullOrEmpty() ->
                "Google is not the default assistant"
            else -> null
        }
    }

    fun probe(context: Context): Readiness { /* ... */ }
    fun trigger(context: Context, entryPoint: Int = DEFAULT_ENTRY_POINT): Boolean { /* ... */ }
}
```

Существенные детали реализации:

- **Проба `contextualSearchKey`.** `Resources.getSystem().getIdentifier(
  "config_defaultContextualSearchKey", "string", "android")`, и если id != 0 —
  прочитать значение. Непустая строка означает, что прошивка сама умеет
  CSHelper-путь: тогда CtS поднимется **без** смены ассистента по умолчанию.
  Это лучший сценарий для OriginOS 6 (Android 16), и его надо проверить до того,
  как гнать пользователя менять ассистента. MiCTS для этой проверки использует
  ту же строку в `TriggerService.CSHelper`.
- **Ветвление по API:** на API ≥ 34 метод четырёхаргументный (+`attributionTag`),
  ниже — трёхаргументный. `attributionTag` передаём своё, например `"bluelm_key"`.
- **Кеш.** `Class.forName` + `getMethod` один раз в `@Volatile` поле, как
  `OriginOs.cached`. Клавишный путь и так уже нагружен.
- **Диагностика.** Каждый вызов — `InterceptorStateRepository.diag("CTS", ...)`
  с результатом и причиной отказа: этот путь молчаливый, без diag его не отладить.
- **Никакой вибрации** (у MiCTS она есть, потому что у них нет другого фидбэка);
  у нас поведение кнопки и так уже определено.

### Шаг 3 — новое действие

`model/TargetAction.kt` — новая константа:
```kotlin
CIRCLE_TO_SEARCH(
    title = "Circle to Search",
    description = "Google's screen search overlay (needs Google app as assistant)"
),
```

Порядок в enum — важен: `MainScreen.kt:528` рендерит `TargetAction.entries`
подряд, а `InterceptorStateRepository` хранит действие по имени
(`fromName`), не по ordinal — так что вставка новой константы безопасна для
уже сохранённых настроек. Логично поставить сразу после `DEFAULT_ASSISTANT`.

Правки-спутники (иначе `when` не скомпилируется / UI будет неполным):
- `ActionExecutionEngine.executeAction` (`:167`) — ветка → `CircleToSearch.trigger(context)`,
  с фолбэком на `Toast` + `diag` при `false`.
- `MainScreen.getActionIcon` (`:1057`) — иконка (`AppIcons` — добавить, например,
  `ScreenSearchDesktop`/`Search`).
- `MainScreen.WillLaunchSummary` (`:1391`) — строка описания.
- Тесты `MainScreenTest`/`ActionExecutionEngineTest`, если там есть
  исчерпывающие `when` по действиям.

### Шаг 4 — интеграция в клавишный путь

- **Shutter** (`:396`) — ничего специального, обычная ветка `executeAction`.
- **BlueLM** (`fireBlueLMAction`, `:587`) — учесть 3.4: если действие
  `CIRCLE_TO_SEARCH`, добавить дополнительную задержку после BACK перед
  вызовом. Предлагаю константу `CTS_SETTLE_MS = 250` поверх текущего
  `dismissDelayMs`, а не новую настройку в UI — сначала померить на устройстве,
  выносить в настройки только если 250 мс не хватит.
- Проверить, что `CIRCLE_TO_SEARCH` не попадает в дабл-пресс-дедуп по-другому,
  чем остальные действия (не должно — путь общий).

### Шаг 5 — гейт в UI

Когда выбрано `CIRCLE_TO_SEARCH`, показывать статус `CircleToSearch.probe()`:

- Google не установлен → кнопка в Play Store.
- Google не ассистент по умолчанию **и** `contextualSearchKey` пуст →
  предупреждение + кнопка `Settings.ACTION_VOICE_INPUT_SETTINGS`
  (или `ACTION_ASSIST_GESTURE_SETTINGS` / общий экран приложений по умолчанию —
  на vivo интент может не резолвиться, обязательно `resolveActivity` + фолбэк
  на текстовую инструкцию).
- Отдельная строка про «снимите ограничения фона для Google» (3.3).

Пробу дёргать при открытии экрана, не на каждый кадр Compose.

### Шаг 6 — тесты

В стиле уже имеющихся (`OriginOs`-подобные чистые функции):
- `Readiness.usable` / `Readiness.blocker` — матрица из 4 флагов.
- `SHOW_FLAGS == 7`.
- `TargetAction.fromName("CIRCLE_TO_SEARCH")` и то, что старые сохранённые
  значения не поехали.
- Ветка `executeAction` для нового действия (там, где сейчас мокается движок).

Сам вызов системного сервиса unit-тестами не покрывается — только устройство.

### Шаг 7 — приёмка на устройстве

- [ ] BlueLM-кнопка на домашнем экране → CtS, обводится домашний экран.
- [ ] BlueLM-кнопка внутри приложения (Telegram/браузер) → в скриншоте нет
      остатков Jovi/BlueLM.
- [ ] Shutter-кнопка → CtS.
- [ ] Повторное нажатие, пока CtS открыт — ничего не ломается.
- [ ] Экран заблокирован → вызов не срабатывает или срабатывает предсказуемо.
- [ ] После суток без открытия Google (заморозка) — всё ещё работает.
- [ ] В diag-логе видны события `CTS` с результатом.

---

## 5. Оценка и порядок

| Шаг | Объём |
|---|---|
| 0. Разведка на устройстве | 15–30 мин, **делать первым** |
| 1. Зависимость + init | 15 мин |
| 2. `CircleToSearch.kt` | ~150 строк, 1–2 ч |
| 3. Действие + UI-спутники | ~50 строк, 1 ч |
| 4. Клавишный путь | ~20 строк, 30 мин + замеры |
| 5. UI-гейт | ~80 строк, 1–1.5 ч |
| 6. Тесты | ~100 строк, 1 ч |
| 7. Приёмка | 30 мин |

Итого ~5–6 часов чистой работы, из них половина — UI и объяснение
пользователю, почему оно может не работать.

---

## 6. Риски и запасные варианты

| Риск | Вероятность | Что делаем |
|---|---|---|
| Google гейтит CtS для модели (`not enabled`) | Средняя | Без root не лечится. Показываем причину в UI, не делаем вид, что «просто не сработало» |
| Пользователь не хочет менять ассистента по умолчанию | Высокая | Сначала проверяем `contextualSearchKey`: если ROM умеет CSHelper — менять не надо |
| vivo морозит Google → CtS всплывает с задержкой | Высокая | Инструкция в UI; кодом не чинится |
| Скриншот захватывает оверлей Jovi | Средняя | `CTS_SETTLE_MS`, подобрать на устройстве |
| AOSP закроет `showSessionFromSession` без токена | Низкая, но растёт | `probe()` вернёт `serviceAvailable = false` → действие деградирует, а не падает |
| GPL-заражение | Нулевая при своей реализации | Не копируем код MiCTS |

**Суррогат, если VIS-путь мёртв:** `SPECIFIC_APP` → Google Lens
(`com.google.ar.lens` / Lens внутри GSA). Это не Circle to Search — нет
обводки поверх текущего экрана, открывается камера/галерея. Честно называть
это в UI по-другому, не «Circle to Search».

---

## 7. Что нужно уточнить

1. Модель аппарата и версия (OriginOS 5 / 6, Android 15 / 16)?
2. Есть ли root / LSPosed? Если да — открывается путь CSHelper без смены
   ассистента и спуфинг модели против гейтинга.
3. Готовы ли сделать Google ассистентом по умолчанию, зная, что
   `DEFAULT_ASSISTANT` тоже переключится на Google?

Источники: [MiCTS](https://github.com/parallelcc/MiCTS) ·
[релизы](https://github.com/parallelcc/MiCTS/releases) ·
[тема на XDA](https://xdaforums.com/t/mod-lsposed-micts-trigger-circle-to-search-on-any-android-9-15-device.4703455/)
