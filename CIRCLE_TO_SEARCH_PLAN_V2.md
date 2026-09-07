# Circle to Search — план V2 (упрощение)

Претензия справедлива наполовину, и та половина, где ты прав, серьёзная:
**я потащил из MiCTS их реализацию, хотя у нас уже есть свой, более короткий
вход в ту же систему.** Ниже — что выбросить.

Но одна вещь в постановке неверна, и от неё зависит вся конструкция.

---

## 1. Почему «просто интент» не сработает

> Скрин CtS сделает сам, на то гугл и стоит помощником.

Google-апп **не делает скриншот и не может его сделать**. У него нет ни
`MediaProjection`, ни системного разрешения на захват экрана. Скриншот делает
`system_server` и **отдаёт** его ассистенту — через колбэк
`VoiceInteractionSession.onHandleScreenshot(Bitmap)`.

Условие доставки ровно одно: сессия ассистента поднята с флагом
`SHOW_WITH_SCREENSHOT`. Поднять сессию с флагами может только система.

«Google стоит помощником» — это условие того, что система **имеет право**
отдать ему скриншот. Но отдаёт она его не по интенту, а по сессии.

Что происходит с обычным интентом:

| Что шлём | Что получает Google | Итог |
|---|---|---|
| `ACTION_ASSIST` | интент без `AssistStructure`, без скриншота | обычный ассистент/Gemini |
| `ACTION_VOICE_COMMAND` | то же | голосовой поиск |
| launcher-интент GSA | ничего | домашний экран Google |

Экран для обводки взять неоткуда — CtS открыться нечем.

Вторая половина проблемы: даже с сессией и скриншотом Google по умолчанию
откроет **обычный ассистент**. Режим Circle to Search выбирается ключом
`omni.entry_point` в `sessionArgs`. Ключ в интент не положишь — sessionArgs
и extras интента это разные каналы.

### Проверить это можно за три минуты, без единой строки кода

```bash
adb shell am start -a android.intent.action.ASSIST
```
Откроется ассистент. Не CtS. Это опровергает «хватит интента».

```bash
adb shell input keyevent 219
```
`KEYCODE_ASSIST` — системный assist-жест, сессия **со скриншотом**. Если
откроется обычный Google-ассистент, а не CtS — доказано, что скриншот сам по
себе ничего не решает, режим задаёт `omni.entry_point`.

Если же на твоём аппарате `keyevent 219` **сразу открывает Circle to Search** —
задача схлопывается до одной строки: `performGlobalAction`/инъекция
`KEYCODE_ASSIST` из сервиса, и весь `CircleToSearch.kt` можно удалить целиком.
**Это первое, что надо проверить.**

---

## 2. Где я действительно намудрил

В `ActionExecutionEngine` уже есть рабочий вход в систему — `invokeSystemAssistGesture`
(строка ~369). Он зовёт скрытый `SearchManager.launchAssist(Bundle)`, и по коду
видно, что на этом аппарате он работает.

Что происходит по AOSP дальше:

```
SearchManager.launchAssist(args)
  └─ SearchManagerService.launchAssist(userId, args)
       └─ StatusBarManagerInternal.startAssist(args)
            └─ SystemUI AssistManager.startAssist(args)
                 └─ AssistUtils.showSessionForActiveService(args, SHOW_SOURCE_ASSIST_GESTURE, ...)
                      └─ VoiceInteractionManagerService.showSessionForActiveService:
                         mImpl.showSessionLocked(args,
                             sourceFlags | SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT, ...)
```

Два вывода:

1. **Флаги скриншота система дорисовывает сама.** Наши `SHOW_FLAGS = 7` не нужны.
2. **`args` проходит насквозь до сессии ассистента.** А мы сейчас передаём туда
   `null` — вот эта строка в существующем коде:

```kotlin
val args = Array(launchAssist.parameterCount) { index ->
    when (launchAssist.parameterTypes[index]) {
        Bundle::class.java -> null      // <- здесь
        ...
```

**Гипотеза V2: достаточно положить в этот Bundle `omni.entry_point` — и всё.**
Ни `ServiceManager`, ни `IVoiceInteractionManagerService$Stub`, ни
`HiddenApiBypass`, ни `showSessionFromSession`. Один уже работающий вызов
плюс два ключа в бандле.

---

## 3. План V2

### Шаг 1 — эксперимент (30 минут, до любого рефакторинга)

Проверить по порядку, остановиться на первом, что сработало:

| # | Что | Если сработало |
|---|---|---|
| 1 | `adb shell input keyevent 219` | удалить `CircleToSearch.kt`, действие = инъекция KEYCODE_ASSIST |
| 2 | `launchAssist(bundle с omni.entry_point)` | оставить ~40 строк, всё остальное удалить |
| 3 | текущий `CircleToSearch.trigger` (уже написан) | оставить как есть, но убрать лишнее (шаг 3) |
| 4 | ничего | Google гейтит модель — см. V1, §6 |

Проверка #2 — временный код прямо в `invokeSystemAssistGesture`, коммитить не надо:

```kotlin
Bundle::class.java -> Bundle().apply {
    putInt("omni.entry_point", 1)
    putLong("invocation_time_ms", System.currentTimeMillis())
}
```

Логи в соседнем окне:
```bash
adb logcat -c && adb logcat | grep -iE "omni|assist|contextualsearch"
```

### Шаг 2 — если победил вариант #2, переписать действие так

`ActionExecutionEngine`:

```kotlin
/** Bundle идёт насквозь до сессии ассистента; SHOW_WITH_SCREENSHOT система ставит сама. */
fun invokeSystemAssistGesture(context: Context, args: Bundle? = null): Boolean { ... }

fun triggerCircleToSearch(context: Context): Boolean =
    invokeSystemAssistGesture(context, Bundle().apply {
        putInt("omni.entry_point", 1)
        putLong("invocation_time_ms", System.currentTimeMillis())
    })
```

Заодно поправить выбор метода — сейчас берётся первый попавшийся с именем
`launchAssist`, а надо тот, у которого ровно один параметр `Bundle`:

```kotlin
val launchAssist = searchManager.javaClass.methods.firstOrNull {
    it.name == "launchAssist" && it.parameterTypes.size == 1 &&
        it.parameterTypes[0] == Bundle::class.java
} ?: return false
```

### Шаг 3 — что удалить из уже написанного `CircleToSearch.kt`

Файл сейчас ~270 строк. Кандидаты на снос независимо от исхода эксперимента:

| Удалить | Почему |
|---|---|
| `SHOW_FLAGS`, `FLAG_*` | система ставит флаги сама на пути `launchAssist` |
| `exemptHiddenApis`, зависимость `hiddenapibypass` | нужна только для `showSessionFromSession` |
| `resolveShow`, `CachedShow`, `pickShowSessionMethod` | то же |
| `googleInstallIntent`, `googleInstallUris` | не наша забота вести в Play Store |
| `batteryHint` | текст в коде движка; место ему в strings.xml, если вообще нужен |
| `ATTRIBUTION_TAG` | на пути `launchAssist` его ставит SystemUI |

Оставить: `probe`/`Readiness` (реально нужен, чтобы объяснить отказ),
`isGoogleAssistantSetting`, `readContextualSearchKey`, `assistantSettingsIntent`,
`extraSettleMs`.

Ожидаемый объём после чистки — **60–80 строк вместо 270**.

### Шаг 4 — лестница фолбэков в одном месте

```
triggerCircleToSearch:
  1. launchAssist(bundle)                 // основной, дешёвый
  2. showSessionFromSession(...)           // текущая реализация, если vivo SystemUI режет bundle
  3. diag + Toast с причиной из probe()
```

Порядок именно такой: путь #1 переживёт обновления Android, #2 — нет.

### Шаг 5 — что не меняется из V1

- Гонка со сворачиванием Jovi (`CTS_SETTLE_MS`) — остаётся, скриншот снимается
  в момент вызова.
- Google должен быть ассистентом по умолчанию — остаётся, `launchAssist`
  показывает сессию **активного** сервиса, то есть Jovi, пока не переключишь.
- Заморозка Google на vivo — остаётся.
- Серверный гейтинг Google — остаётся, кодом не лечится.

Упрощается реализация. Условия работы — те же, они не в нашей власти.

---

## 4. Итог

| | V1 | V2 |
|---|---|---|
| Вход в систему | `ServiceManager` + `IVoiceInteractionManagerService` | уже работающий `SearchManager.launchAssist` |
| Новые зависимости | `hiddenapibypass` | нет |
| Строк кода | ~270 | ~60–80 |
| Флаги/скриншот | руками | система |
| Живучесть при обновлении Android | средняя | высокая |

Порядок действий: **сначала `keyevent 219`, потом бандл в `launchAssist`, и
только если оба мимо — оставляем то, что уже написано.**
