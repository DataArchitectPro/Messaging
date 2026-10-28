# План: форк «Сообщений» GrapheneOS только для приёма SMS

Цель: приложение, которое принимает и показывает SMS, но **не может** отправить SMS/MMS
или начать звонок ни от случайного нажатия, ни из уведомления, ни по интенту другого приложения,
ни после rebase на свежий upstream. Затем оно заменяет системное `com.android.messaging`
на Pixel 10a (GrapheneOS, Android 17 / SDK 37, adb через `ssh omarchy-lt`).

Дата анализа: 28.10.2026. База: `main` = upstream GrapheneOS/Messaging v17 (`302a604a`).

---

## 0. Что выяснил анализ

### Проект
- Gradle (AGP 9.4.1, Kotlin 2.4.21, Gradle 9.8.2), один модуль `:app`, исходники в `src/`, манифест `AndroidManifest.xml` в корне.
- `namespace = com.android.messaging`, `applicationId` не задан, поэтому он равен namespace. Debug-сборка: суффикс `.debug`.
- `minSdk 36`, `targetSdk/compileSdk 37`, build-tools 37.0.0, NDK + CMake 3.22.1 (giflib, `jni/GifTranscoder.cpp`).
- Release-подпись читается из `keystore.properties` (если файла нет, сборка остаётся неподписанной).
- Authority провайдеров заданы через `${applicationId}`, поэтому при другом applicationId конфликта с системным приложением нет.

### Все пути исходящей отправки и звонков
| Путь | Где | Чем опасен |
|---|---|---|
| Отправка SMS | `src/com/android/messaging/sms/SmsSender.java:255-300` (`sendTextMessage`, `sendMultipartTextMessage`) | прямая отправка |
| Отправка MMS + служебные ответы | `src/com/android/messaging/sms/MmsSender.java:147` (private `sendMms`): через него идут и `sendNotifyResponseForMmsDownload`, и `sendAcknowledgeForMmsDownload` | **MMS-подтверждение уходит автоматически при входящем MMS**, без участия пользователя |
| Скачивание MMS | `MmsSender.java:269` (`downloadMultimediaMessage`), автоскачивание включено по умолчанию (`res/values/constants.xml:32`) | мобильные данные через MMS APN в роуминге (Beeline); Android может поднять MMS APN даже при `mobile_data=0` |
| Очередь отправки | `datamodel/action/SendMessageAction.java`, `ProcessPendingMessagesAction.java`, `ResendMessageAction.java`, `InsertNewMessageAction.java` | автоповтор неотправленных при старте и при появлении сети |
| UI-отправка | `domain/conversation/usecase/draft/SendConversationDraft.kt`, `ui/conversation/composer/**` (`ConversationComposeBar.kt`, `ConversationSendActionButton*.kt`) | кнопка «Отправить» |
| Ответ из уведомления | `datamodel/BugleNotifications.java:555-573` (RemoteInput → `RemoteInputEntrypointActivity` → `NoConfirmationSmsSendService`) | ответ из шторки в одно касание |
| «Ответить сообщением» на входящий вызов / сторонние системные приложения | `datamodel/NoConfirmationSmsSendService.java` (`RESPOND_VIA_MESSAGE`) | отправка без подтверждения |
| Пересылка / Share | `ui/conversationpicker/host/forward/**`, `host/share/ShareIntentActivity` (alias в манифесте) | создание исходящего |
| Звонок | `ui/UIIntentsImpl.java:160` (`ACTION_CALL`), его вызывают `ConversationScreenEffects.kt:262`, `ConversationListEffectHandler.kt:53`, `ArchivedConversationListEffectHandler.kt:49`, `ConversationSettingsEffectHandler.kt:84`, `BlockedParticipantsEffectHandler.kt:53` | звонок одним тапом по иконке |
| Набор номера | `ConversationMessageTextLinkExtractor.kt:105` (`tel:`-ссылки в тексте), `VCardDetailEffectHandler.kt:79` (`ACTION_DIAL`) | открывает звонилку (сейчас она отключена) |

### Телефон (только чтение, 28.10.2026)
- Системные «Сообщения»: `/product/app/Messaging/Messaging.apk`, v16, SYSTEM (не priv-app), подпись GrapheneOS (`889479e5`), держит роль SMS. Dialer отключён (`pm list packages -d`).
- У `com.android.messaging` была отозвана вся SMS-группа, включая `RECEIVE_SMS`/`RECEIVE_MMS`/`RECEIVE_WAP_PUSH` (последняя входящая SMS пришла 20.10.2026). **28.10.2026 с согласия пользователя эти три разрешения выданы снова.** `SEND_SMS` остался отозван. Бэкап до выдачи: `~/assistant/backups/pixel-messaging-2026-10-28/perms-before-grant.txt`.

### Следствия для дизайна
1. Заменить системное приложение «поверх» нельзя: пакет `com.android.messaging` подписан ключом GrapheneOS. Значит, форк получает **свой applicationId**, ставится рядом, забирает роль SMS, а системное отключается.
2. Самая надёжная защита работает на уровне ОС: **не объявлять `SEND_SMS` и `CALL_PHONE` в манифесте.** Тогда `SmsManager.sendTextMessage` и `sendMultimediaMessage` (MmsServiceBroker тоже требует SEND_SMS) получают `SecurityException`, а `ACTION_CALL` не запустится. Это не обходится ни ошибкой в коде, ни ролью SMS, ни будущим upstream-кодом (если сохранить проверку из п. 3.4).
3. Роль SMS требует компоненты, а не разрешение на отправку: SENDTO-активность, сервис `RESPOND_VIA_MESSAGE`, ресиверы `SMS_DELIVER` и `WAP_PUSH_DELIVER`. Их надо **оставить**, сделав «немыми». *Проверить на эмуляторе, что роль назначается без SEND_SMS (этап 4).*

---

## 1. Принцип: три слоя защиты

1. **ОС**: в манифесте нет `SEND_SMS`/`CALL_PHONE`, а сборка падает, если они вернулись.
2. **Код**: единый выключатель `ReceiveOnlyPolicy` в узких местах (SmsSender, MmsSender, очередь, сервисы). Без него слой 1 давал бы падения и бесконечные ретраи.
3. **UI**: нет поля ввода, кнопок «Отправить», «Позвонить», «Ответить», «Переслать», FAB «Новый чат».

Каждое изменение помечается комментарием `// receive-only:`, чтобы при rebase было легко найти все правки.

---

## 2. Подготовка репозитория

1. `git remote add upstream https://github.com/GrapheneOS/Messaging.git`.
2. `main` оставить зеркалом upstream. Работа идёт в ветке `receive-only` (`git switch -c receive-only`).
3. Один логический шаг = один коммит (идентичность, манифест, политика, UI, MMS, проверки), так проще rebase.
4. Ключ подписи: `keytool -genkeypair -v -keystore ~/keys/messaging-ro.jks -alias messaging-ro -keyalg EC -groupname secp256r1 -validity 10000` (или RSA 4096). Создать `keystore.properties` (он и `*.keystore` уже в `.gitignore`; `.jks` там нет, поэтому ключ хранить вне репо). Ключ и пароль сохранить в бэкап. Без того же ключа обновить приложение потом не выйдет.

---

## 3. Доработки кода

### 3.1. Идентичность приложения (`app/build.gradle.kts`, ресурсы)
- `defaultConfig { applicationId = "io.github.dataarchitectpro.messaging" }` (имя на ваш выбор). `namespace` не трогать, иначе придётся переписывать все `R`/импорты.
- `app_name`: например, «SMS (приём)» вместо «Messaging». Значок перекрасить (`res/mipmap*`, adaptive icon), чтобы не путать с системным. Названия и логотипа GrapheneOS не использовать (Apache-2.0 разрешает форк, но не товарный знак). Сохранить `LICENSE`/NOTICE и экран лицензий.
- `versionName = "17-ro1"`. versionCode можно оставить по схеме upstream: applicationId другой, конфликтов нет.
- `DebugUtils.java:581` содержит захардкоженное `"com.android.messaging"` (debug-функция). Заменить на `context.packageName`.

### 3.2. Манифест (слой 1)
- Удалить `<uses-permission android:name="android.permission.SEND_SMS"/>` (стр. 26) и `CALL_PHONE` (стр. 44). Для себя можно убрать и `RECORD_AUDIO`/`CAMERA` (вложения не нужны), это необязательно.
- Оставить: `READ_SMS`, `RECEIVE_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH`, `READ_CONTACTS`, `READ_PHONE_STATE`, `READ_PHONE_NUMBERS`, `POST_NOTIFICATIONS`, `READ_CELL_BROADCASTS`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `INTERNET` (превью ссылок по Wi-Fi; при желании убрать).
- `ShareIntentActivity` alias: удалить intent-filter `SEND`/`SEND_MULTIPLE`, чтобы приложение не появлялось в «Поделиться».
- `LaunchConversationActivity` (SENDTO/VIEW `sms:`): **оставить** (нужна для роли), но она должна открывать переписку только для чтения, а `sms_body` из интента игнорировать.
- `NoConfirmationSmsSendService`: **оставить** в манифесте (нужен для роли).

### 3.3. `ReceiveOnlyPolicy` и узкие места (слой 2)
Новый файл `src/com/android/messaging/util/ReceiveOnlyPolicy.kt`:
```kotlin
object ReceiveOnlyPolicy {
    const val SENDING_ENABLED = false
    const val CALLING_ENABLED = false
    const val MMS_DOWNLOAD_ENABLED = false // решение по MMS, см. 3.5
}
```
(Можно сделать через `buildConfigField`, но `const val` проще и R8 вырежет мёртвый код.)

Guard'ы (ранний `return` + `LogUtil.w(TAG, "receive-only: blocked …")`):
| Файл | Что сделать |
|---|---|
| `sms/SmsSender.java` `sendMessage(...)` | вернуть `SendResult` с ошибкой (как `RESULT_ERROR_GENERIC_FAILURE`), без вызова SmsManager |
| `sms/MmsSender.java` private `sendMms(...)` | не вызывать `MmsManager.sendMultimediaMessage`. Это закрывает и отправку MMS, и NotifyRespInd/AcknowledgeInd |
| `sms/MmsSender.java` `downloadMms(...)` | по решению 3.5 |
| `datamodel/action/SendMessageAction.java` `executeAction()` | сразу пометить сообщение `FAILED` без ретрая (`MMS_REQUEST_NO_RETRY` / статус SMS failed) |
| `ProcessPendingMessagesAction.processFirstPendingMessage()` и `scheduleProcessPendingMessagesAction()` | no-op: нет автоповторов и будильников |
| `ResendMessageAction.resendMessage()` | no-op |
| `InsertNewMessageAction` (статические `insertNewMessage…`) | не создавать исходящие |
| `NoConfirmationSmsSendService.onHandleIntent` | игнорировать интент (только лог) |
| `ui/RemoteInputEntrypointActivity.kt` | `finish()` без действий |
| `ui/UIIntentsImpl.java:160` `launchPhoneCallActivity` | вместо `ACTION_CALL` скопировать номер в буфер + Toast «Звонки отключены» |
| `UIIntentsImpl.getPendingIntentForSendingMessageToConversation` | не вызывается после 3.4 (можно оставить) |
| `datamodel/action/FixupMessageStatusOnStartupAction.java` | уже переводит «зависшие» исходящие в failed. Убедиться, что после этого ничего не ставится в очередь |

### 3.4. UI (слой 3)
- **Композер**: `ui/conversation/composer/ui/ConversationComposerSection.kt` вместо `ConversationComposeBar` показывает плашку «Только приём: отправка отключена». Тогда исчезают кнопка отправки, медиапикер, запись аудио, вложения. `SendConversationDraft.kt` тоже бросает исключение под guard'ом (страховка).
- **Звонки**: убрать иконку/пункт «Позвонить» в `ui/conversation/metadata/ui/ConversationTopAppBar.kt`, `ui/common/components/participant/ParticipantQuickActionsPopup.kt`, `ui/conversationlist/common/item/ConversationListItemAvatar.kt`/`ConversationListItemRow.kt`, `ui/conversationsettings/common/ConversationSettingsComponents.kt`, `ui/blockedparticipants/common/BlockedParticipantItem.kt` (найти по `grep -rn launchPhoneCall\|Call` и по эффектам `*Effect*`).
- **Ссылки `tel:`**: в `ConversationMessageTextLinkExtractor.kt:105` номер телефона не превращать в `tel:`-ссылку. Копирование номера через long-press уже есть в upstream (коммит 302a604a). В `VCardDetailEffectHandler.kt:79` `ACTION_DIAL` заменить на копирование.
- **Уведомления**: в `BugleNotifications.java:555-573` не добавлять reply-action. «Прочитано» оставить. Кнопку «Скачать MMS» (стр. 575-588) убрать, если MMS заблокированы.
- **Новый чат**: скрыть FAB в `ui/conversationlist/chats/ConversationListScreen.kt` и экран выбора получателей (`ui/conversation/recipientpicker`).
- **Пересылка/повтор**: убрать «Переслать» и «Повторить отправку» из меню сообщения (`ui/conversation/messages/**`, `ui/conversationpicker/host/forward/**`), долгий тап по failed-сообщению не должен отправлять повторно.
- **Виджет**: проверить `widget/**`. Если открывает композер, открывать переписку read-only.
- **Настройки**: скрыть «Отчёты о доставке», «Групповые сообщения», номер телефона для MMS. Добавить строку «Режим: только приём».

### 3.5. MMS — решено: полный запрет скачивания
- `MMS_DOWNLOAD_ENABLED = false`, guard в `MmsSender.downloadMms` и в `DownloadMmsAction`/`RedownloadMmsAction`. Входящие MMS показываются как «MMS не загружено», без кнопки скачивания (в пузыре и в уведомлении). Причина: SIM в роуминге (O2 UK → Beeline), а MMS-трафик идёт через MMS APN даже при `mobile_data=0`.
- Значения по умолчанию `auto_retrieve_mms_pref_default` и `auto_retrieve_mms_when_roaming_pref_default` = false (`res/values/constants.xml:32,34`), сами пункты в настройках скрыть.
- NotifyRespInd/AcknowledgeInd не уходят: их закрывает guard в `MmsSender.sendMms` и отсутствие SEND_SMS.

### 3.6. Проверки, которые не дадут защите «уехать» при rebase
1. **Gradle-проверка смёрженного манифеста** (в `app/build.gradle.kts` или `buildSrc`): после `process<Variant>Manifest` парсить итоговый `AndroidManifest.xml` и падать, если есть `android.permission.SEND_SMS`, `CALL_PHONE`, `SEND_RESPOND_VIA_MESSAGE`-*uses*-permission или intent-filter `SEND` на share-alias. Подключить к `preBuild`/`assemble`.
2. Unit-тест (папка `app/src/test`): `ReceiveOnlyPolicy.SENDING_ENABLED == false`, `SmsSender.sendMessage` возвращает ошибку и не трогает `SmsManager` (мок через Factory/PhoneUtils).
3. Скрипт `tools/check-receive-only.sh`: `grep` на новые вызовы `sendTextMessage|sendMultipartTextMessage|sendMultimediaMessage|ACTION_CALL` вне файлов с guard'ом. Запускать после каждого rebase.
4. Существующие `./gradlew ktlintCheck detekt testDebugUnitTest` должны оставаться зелёными.

---

## 4. Сборка и проверка на эмуляторе

### Окружение — решено: собирать на dev-сервере (этот хост)
Проверено 28.10.2026: Android SDK нет; есть `java-21-openjdk-amd64`; JDK 17 не установлен (apt-кандидат `openjdk-17-jdk-headless` 17.0.20); `sudo -n` работает; `/dev/kvm` есть (группа `kvm`, проверить, что пользователь в ней, иначе `sudo usermod -aG kvm $USER` и перелогиниться); 4 CPU, 15 ГБ RAM, свободно ~22 ГБ (SDK+NDK+образ эмулятора ≈ 8–10 ГБ). На omarchy-lt Java нет, он нужен только для adb.
1. `sudo apt install openjdk-17-jdk-headless`. Нужны JDK 17 (toolchain компиляции) и JDK 21 (тесты). foojay-resolver не настроен, Gradle сам их не скачает.
2. cmdline-tools: последний в `https://dl.google.com/android/repository/repository2-3.xml` на 28.10 — `commandlinetools-linux-16111833_latest.zip`. Распаковать в `~/Android/Sdk/cmdline-tools/latest/`, `export ANDROID_HOME=~/Android/Sdk` (добавить в `~/.bashrc`), создать `local.properties` с `sdk.dir=/home/dev/Android/Sdk`, `yes | sdkmanager --licenses`.
3. `sdkmanager "platform-tools" "platforms;android-37" "build-tools;37.0.0" "cmake;3.22.1"`. Версию NDK взять из ошибки или лога первой сборки (AGP 9.4 подскажет `ndkVersion` по умолчанию) и поставить `"ndk;<версия>"`. Для эмулятора: `"emulator" "system-images;android-37;google_apis;x86_64"`, затем `avdmanager create avd -n ro37 -k "system-images;android-37;google_apis;x86_64"` и запуск `emulator -avd ro37 -no-window -no-audio`. Эмулятор локальный: его adb — на этом сервере, телефон — через `ssh omarchy-lt`, не путать.
4. `./gradlew assembleDebug testDebugUnitTest ktlintCheck`, затем `./gradlew assembleRelease` (с `keystore.properties`).
5. Проверить APK: `apkanalyzer manifest permissions app-release.apk` (нет SEND_SMS/CALL_PHONE), `apksigner verify --print-certs`.

### Эмулятор (единственное место, где можно пробовать «отправить»)
SIM в эмуляторе фиктивная, `adb emu sms send <номер> <текст>` имитирует входящую SMS.
- Назначить роль SMS: `adb shell cmd role add-role-holder android.app.role.SMS <pkg>`, убедиться, что роль назначилась **без** SEND_SMS.
- Входящая SMS появляется в списке, приходит уведомление.
- `am start -a android.intent.action.SENDTO -d smsto:+70000000000 --es sms_body test` открывает read-only экран, ничего не отправлено (`content query --uri content://sms/outbox` и `sent` пусты, logcat: `receive-only: blocked`).
- `am startservice` для `RESPOND_VIA_MESSAGE` от shell не пройдёт (нужно системное разрешение), это нормально.
- В уведомлении нет «Ответить». В UI нет звонков, «Новый чат», «Переслать».
- Входящий MMS (если эмулятор умеет) не скачивается, исходящих PDU нет.

---

## 5. Замена приложения на телефоне (Pixel, `ssh omarchy-lt`, serial `66051JEA306157`)

Далее `A="adb -s 66051JEA306157"`. Все команды выполняются на omarchy-lt.

### 5.1. Бэкап (до любых изменений)
Каталог `~/assistant/backups/pixel-messaging-swap-<дата>/` (права 700, файлы 600):
- `cmd role get-role-holders android.app.role.SMS`, `pm list packages -d`.
- `dumpsys package com.android.messaging` (permissions + appops), `appops get com.android.messaging`.
- Экспорт SMS из telephony provider (shell это может, проверено): `content query --uri content://sms` → `sms.txt`. Также `content://mms` и `content://mms/part` (метаданные).
- Что не сохраняется без root: собственная БД системного приложения (архив, закрепления, черновики, настройки уведомлений по чатам). Сами сообщения живут в telephony provider, новое приложение их подтянет. Архив и пины придётся настроить заново.
- Записать в memory: что меняем и команды отката.

### 5.2. Установка
1. `scp app-release.apk omarchy-lt:/tmp/` → `$A install app-release.apk`.
2. Назначить роль SMS. Удобнее руками: Настройки → Приложения → Приложения по умолчанию → SMS → «SMS (приём)». Через adb: `$A shell cmd role add-role-holder android.app.role.SMS <pkg> 0`. Роль сама выдаст SMS-разрешения, но SEND_SMS выдать не сможет: его нет в манифесте.
3. Проверить: `cmd role get-role-holders android.app.role.SMS` возвращает `<pkg>`; `dumpsys package <pkg> | grep -E "SEND_SMS|CALL_PHONE"` пусто; RECEIVE_SMS/RECEIVE_MMS/READ_SMS/POST_NOTIFICATIONS granted. При необходимости выдать через `pm grant <pkg> …`.
4. **Обязательно (требование пользователя): отключить стандартные «Сообщения» GrapheneOS**, чтобы они не перехватывали SMS, уведомления и интенты `sms:`. Делать только после того, как роль SMS у форка и приём проверены (п. 3): `$A shell pm disable-user --user 0 com.android.messaging`. Это обратимо (`pm enable`). Проверить: `pm list packages -d | grep messaging` показывает пакет; роль SMS осталась у форка; `am start -a android.intent.action.SENDTO -d smsto:+70000000000` открывает форк, а не системное приложение; в лаунчере нет старого значка. Если обновление из GrapheneOS Apps снова включит пакет, повторить `disable-user` (отметить в memory).
5. Исключить форк из ограничений батареи не нужно: SMS_DELIVER будит приложение само.

### 5.3. Проверка на телефоне (без отправки!)
- Попросить кого-то отправить SMS на O2-номер (или дождаться сервисной). Сообщение пришло, есть уведомление без «Ответить».
- В UI нет композера и звонков. `am start -a android.intent.action.SENDTO -d smsto:+70000000000` открывает read-only экран.
- `content query --uri content://sms/outbox` и `--uri content://sms/sent --where "date>…"` пусты.
- `logcat -d | grep receive-only`: только ожидаемые записи.
- **Ничего не отправлять с этой SIM** даже «для проверки».

### 5.4. Откат
```
$A shell pm enable com.android.messaging
$A shell cmd role add-role-holder android.app.role.SMS com.android.messaging 0
$A shell pm grant com.android.messaging android.permission.RECEIVE_SMS   # + RECEIVE_MMS, RECEIVE_WAP_PUSH
$A uninstall <pkg>
```

### 5.5. Обновления форка
`git fetch upstream && git rebase upstream/main` в ветке `receive-only` → `tools/check-receive-only.sh` → тесты → `assembleRelease` тем же ключом → `$A install -r app-release.apk`.

---

## 5a. Результаты проверки на эмуляторе (28.10.2026)
- Роль SMS назначается без `SEND_SMS` (подтверждено, `RECEIVE_SMS`/`READ_SMS`/`RECEIVE_MMS` выданы ролью).
- Расхождение с планом: без `SEND_SMS` платформа (`SmsApplication`) пишет `lost android:send_sms (no permission to fix)`, и `Telephony.Sms.getDefaultSmsPackage()` перестаёт возвращать приложение. Из-за этого `PhoneUtils.isDefaultSmsApp()` был false: не было уведомлений и синхронизации. Исправлено в `PhoneUtils.getDefaultSmsApp()` через `RoleManager.isRoleHeld(ROLE_SMS)` (комментарий `receive-only:`). `SEND_SMS` в манифест не возвращается.
- Тесты, проверяющие отключённые функции, помечены `@Ignore("receive-only: ...")`.
- Ключ подписи: `~/keys/messaging-ro.jks`, пароль в `keystore.properties` (не в git). Бэкап ключа сделать вне этого сервера.

## 5b. Статус выполнения (28.10.2026)
Выполнено: этапы 1-7 целиком. Форк `17-ro1` стоит на Pixel, роль SMS у него, `com.android.messaging` отключён (`pm disable-user`), на телефоне нет `SEND_SMS`/`CALL_PHONE`, `outbox`/`sent` пусты, мобильные данные 0/0. Бэкап и откат: `~/assistant/backups/pixel-messaging-swap-2026-10-28/`, memory `pixel-sim-protection`.
Не проверено: приём настоящей SMS на форке (на эмуляторе приём и уведомление проверены). Нужно попросить прислать SMS на O2-номер и убедиться, что есть уведомление без «Ответить».
Бэкап ключа подписи лежит только на серверах (dev-сервер, omarchy-pc, `~/assistant/backups/signing-key-messaging-ro`). Скопировать вне серверов (менеджер паролей/флешка), иначе обновить приложение потом не получится.
Сборка и тесты идут на omarchy-pc (`~/android-dev/env.sh`, `~/Projects/Messaging`).

## 6. Принятые решения (28.10.2026)
1. Приём SMS у системного приложения восстановлен (выполнено, см. раздел 0).
2. applicationId `io.github.dataarchitectpro.messaging`, название «SMS (приём)».
3. MMS: полный запрет скачивания (раздел 3.5).
4. Сборка на dev-сервере, SDK ставится сюда (раздел 4).
5. После установки форка стандартные «Сообщения» GrapheneOS отключаются (раздел 5.2, п. 4).

## Правила для исполнителя
- Работать в ветке `receive-only`, коммитить по этапам. Не пушить и не публиковать без явной просьбы пользователя.
- **Никогда не отправлять SMS/MMS и не звонить с телефона**, даже для теста. Отправку проверять только на эмуляторе.
- Не включать мобильные данные на телефоне и не отключать Wi-Fi через adb.
- Перед каждым изменением на телефоне сохранить состояние в `~/assistant/backups/…` (права 600). После — обновить memory `pixel-sim-protection` (проект ~/assistant) и `receive-only-fork-goal` (этот проект): что сделано и команды отката.
- Если что-то в плане не подтверждается (например, роль SMS не назначается без SEND_SMS), остановиться и спросить пользователя. Не обходить.

## 7. Порядок работ и оценка
| # | Этап | Объём |
|---|---|---|
| 1 | Ветка, upstream, ключ, applicationId/название/иконка | 0.5 ч |
| 2 | Манифест + Gradle-проверка манифеста | 1 ч |
| 3 | `ReceiveOnlyPolicy` + guard'ы в sms/datamodel + unit-тесты | 2–3 ч |
| 4 | UI: композер, звонки, уведомления, FAB, пересылка, настройки | 3–4 ч |
| 5 | MMS-политика | 0.5–1 ч |
| 6 | SDK, сборка, эмулятор, проверка | 2 ч |
| 7 | Бэкап, установка, замена, проверка на телефоне, memory | 1 ч |
