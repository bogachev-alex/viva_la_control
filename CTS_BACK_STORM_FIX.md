# CtS работает. Чинить надо BACK-шторм

Триггер удался: `launchAssist` + `omni.entry_point` → `LensientActivity`
(это и есть Circle to Search). Путь V2 подтверждён, `showSessionFromSession`
и `hiddenapibypass` можно выкидывать.

Оба симптома — «скрин не экрана» и «нажимается назад» — это **один баг**, и он
не в CtS, а в нашем сворачивании Copilot.

---

## 1. Что показывает лог

```
17:05:40.173 BLM BACK=true overlay=true BACK2=true   ← 2 BACK сразу
17:05:40.174 BLM hammer BACK=true reason=windows-changed
17:05:40.221 BLM hammer BACK=true reason=repeat
17:05:40.259 BLM hammer BACK=true reason=overlay-followup
17:05:40.359 BLM hammer BACK=true reason=windows-changed
17:05:40.373 FG  pkg=com.bbk.launcher2                ← приложение уже выкинуло на лаунчер
17:05:40.449 BLM hammer BACK=true reason=windows-changed
17:05:40.450 BLM hammer BACK=true reason=windows-changed
17:05:40.527 BLM hammer BACK=true reason=windows-changed
17:05:40.610 BLM overlay leftover BACK again=true
17:05:40.667 BLM hammer BACK=true reason=windows-changed
17:05:40.723 ACT invoked SearchManager.launchAssist   ← снимок делается ЗДЕСЬ
```

**Одиннадцать BACK за 550 мс.** Copilot уходит с первого-второго. Остальные
девять летят в приложение пользователя: на 40.373 мы уже на лаунчере, а снимок
CtS делается только на 40.723 — отсюда «скрин не экрана».

Второй случай, Firefox (17:05:48.848): 4 BACK. Firefox выжил, но получил
навигацию назад по истории — отсюда «еще нажимается назад».

## 2. Почему предохранители не сработали

| Предохранитель | Почему бесполезен |
|---|---|
| `isWithinCopilotSession()` | true всё время жизни `pendingBlueLMLaunchJob` (~350–450 мс), то есть весь опасный интервал |
| `shouldHammerCopilot(actionLaunched, assistForeground)` | `blueLMActionLaunched` станет true только **после** действия; `foregroundPackage` станет Google только **после** открытия CtS |
| `COPILOT_HAMMER_MIN_INTERVAL_MS = 40` | ограничивает частоту, но не количество |
| `shouldExtraBackAfterWait(...)` | true, если окно Copilot видели за последние 400 мс — сразу после перехвата это true по построению |

Корень: **нигде не проверяется, есть ли Copilot на экране прямо сейчас.**
Проверить его через `foregroundPackage` нельзя — оверлей Copilot приходит как
`android.widget.FrameLayout`, `isLikelyActivityWindow` его отсекает, и FG-трекинг
его в принципе не видит. Поэтому весь код угадывает присутствие по таймеру.

Хуже: BACK по `TYPE_WINDOWS_CHANGED` — самоподдерживающийся цикл. Разрушение
Copilot само порождает пачку WINDOWS_CHANGED, каждый из которых шлёт новый BACK,
который порождает следующий WINDOWS_CHANGED.

---

## 3. Исправление

Заменить угадывание по таймеру на факт: `AccessibilityService.getWindows()`.
В `accessibility_service_config.xml` уже стоит `canRetrieveWindowContent="true"`
и `flagRetrieveInteractiveWindows` — список окон нам доступен, доработок манифеста
не нужно.

### Шаг 1 — узнать правду о присутствии Copilot

`BlueLMInterceptorService`, чистая функция в `companion object` (тестируемая):

```kotlin
fun containsCopilotWindow(packageNames: List<String?>, ownPackage: String): Boolean =
    packageNames.any { isBlueLMOrVivoAssistant(it, null, ownPackage) }
```

и обёртка над системой:

```kotlin
private fun copilotWindowPresent(): Boolean =
    containsCopilotWindow(windows.map { it.root?.packageName?.toString() }, ownPackageName())
```

### Шаг 2 — один шлюз для всех BACK

Сейчас BACK шлётся из четырёх мест: `BACK`, `BACK2`, `hammerCopilotDismiss`,
`overlay leftover`. Свести в одну функцию с тремя жёсткими условиями:

```kotlin
private fun dismissCopilotBack(reason: String): Boolean {
    if (blueLMActionLaunched) return false
    if (backPressCount >= MAX_DISMISS_BACKS) return false      // MAX_DISMISS_BACKS = 3
    if (!copilotWindowPresent()) {                              // главное условие
        InterceptorStateRepository.diag("BLM", "skip BACK ($reason): copilot gone")
        return false
    }
    val now = System.currentTimeMillis()
    if (now - lastCopilotHammerMs < COPILOT_HAMMER_MIN_INTERVAL_MS) return false
    lastCopilotHammerMs = now
    backPressCount++
    return performGlobalAction(GLOBAL_ACTION_BACK)
}
```

`backPressCount` сбрасывается там же, где `blueLMActionLaunched = false` —
на новом перехвате и на screen-off.

Жёсткий потолок в 3 нажатия нужен как страховка: если `windows` окажется пустым
или не отдаст пакет оверлея, шторм всё равно не повторится.

### Шаг 3 — заменить реакцию на события опросом

Убрать BACK из ветки `TYPE_WINDOWS_CHANGED` (`onAccessibilityEvent`) — это и есть
источник самоподдерживающегося цикла. Вместо фиксированного `wait=349ms` и
пачки BACK по событиям, `pendingBlueLMLaunchJob` делает так:

```kotlin
pendingBlueLMLaunchJob = serviceScope.launch {
    dismissCopilotBack("initial")
    val deadline = System.currentTimeMillis() + COPILOT_DISMISS_TIMEOUT_MS  // 400
    while (System.currentTimeMillis() < deadline) {
        delay(COPILOT_POLL_MS.milliseconds)                                 // 30
        if (!copilotWindowPresent()) break
        dismissCopilotBack("poll")
    }
    delay(CircleToSearch.extraSettleMs(state.blueLMAction).milliseconds)     // 250 только для CtS
    fireBlueLMAction(state, ownPkg)
}
```

Что это даёт:
- BACK ровно столько, сколько нужно, и ни одного после ухода Copilot;
- действие стартует **сразу**, как оверлей исчез, а не по фиксированной паузе —
  в типичном случае быстрее нынешних 349 мс;
- `dismissDelayMs` (99 мс) остаётся только как нижняя граница ожидания, если она
  вообще нужна после этого.

### Шаг 4 — вычистить то, что стало лишним

| Удалить | Причина |
|---|---|
| `BACK2` (второй безусловный BACK при `overlay`) | покрыт опросом |
| `hammerCopilotDismiss` + вызовы `windows-changed` / `repeat` / `overlay-followup` | источник шторма |
| `shouldExtraBackAfterWait` и «overlay leftover BACK again» в `fireBlueLMAction` | покрыт опросом; сейчас стреляет вслепую |
| `isWithinCopilotSession` / `isWithinCopilotDismissWindow` | заменены на `copilotWindowPresent()` |
| `COPILOT_OVERLAY_RECENT_MS` | больше не по чему угадывать |

### Шаг 5 — диагностика, чтобы это было видно

Перед самым вызовом писать, что попадёт в снимок:

```kotlin
InterceptorStateRepository.diag("CTS", "top=${topWindowPackage()} backs=$backPressCount")
```

Если после фикса в логе `top=org.mozilla.firefox` и `backs=1` — снимок правильный.
Если `top=com.bbk.launcher2` — значит BACK всё ещё лишний.

### Шаг 6 — тесты

Чистые функции, в стиле существующих:
- `containsCopilotWindow` — copilot есть / нет / список пуст / только свой пакет;
- потолок `MAX_DISMISS_BACKS`;
- `extraSettleMs` только для `CIRCLE_TO_SEARCH`.

### Шаг 7 — приёмка

- [ ] Firefox, прокрученная страница, 3 вкладки → BlueLM → CtS поверх Firefox,
      после закрытия CtS та же страница, вкладки целы.
- [ ] В логе `backs=1` или `2`, ни одного `skip BACK ... copilot gone` подряд пачкой.
- [ ] `top=` в диагностике совпадает с тем, что было на экране.
- [ ] Лаунчер → BlueLM → CtS обводит лаунчер (а не пустоту).
- [ ] Двойное нажатие подряд не роняет и не открывает CtS дважды.
- [ ] `SCREENSHOT` на shutter-кнопке не сломан (общий путь BACK не трогали).

---

## 4. Побочное, из того же лога

1. **`hiddenapibypass` и `showSessionFromSession` больше не нужны** — сработал
   `launchAssist`. Снести по плану V2, §3.
2. `blueLM=CIRCLE_TO_SEARCH com.google.android.apps.bard` — в настройках висит
   пакет Gemini как `specificPackage`. Для `CIRCLE_TO_SEARCH` он не используется;
   проверить, что UI не показывает его как «будет запущено».
3. `csKey=(empty)` — CSHelper-путь на этой прошивке недоступен, Google обязан
   оставаться ассистентом по умолчанию. Так и есть, всё работает.
4. `resolve ACTION_ASSIST = com.android.intentresolver/ResolverActivity` —
   отдельное подтверждение, что «просто интент» тут дал бы диалог выбора, а не CtS.
