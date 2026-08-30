# CourierLedger v1.9 — combined v1.7–v1.9

## v2.2 — deletion consistency, protected actions and finance OCR hardening

- Active-order calculations now use DAO-level deletion/merge semantics as the single source of truth. Deleting a customer, route or shift invalidates analytics immediately; deleted routes are also hard-filtered out of the Clients screen until restored.
- Added an explicit data-revision signal for destructive/restorative actions so Statistics and Shifts recalculate immediately even if Room query invalidation is delayed by a nested relation.
- Locked Delete/Edit-time actions now open a modal explaining that Developer Mode is required, with a direct **Go to Developer Mode** action.
- Restored the v2.0 bottom-nav icons: CalendarMonth, BarChart and QrCodeScanner.
- Statistics period chips are horizontally scrollable single-line pills; analytics dropdown items now use rounded bordered cards with a highlighted selection.
- Re-importing an already known finance photo reruns OCR instead of blocking on the duplicate hash, allowing missing/deleted penalties and bonuses to be added again after review.
- Finance-table OCR now anchors amounts to the visual **Částka** column and can read `0`, `125`, `200`, `250`, `1500`, etc. even when ML Kit drops/splits the `Kč` token. Tests cover these currency-less amount tokens.


## v1.9 combined milestone — reminders, audit/trash, backup/security + finance OCR fix

This build deliberately combines the planned v1.7, v1.8 and v1.9 milestones into one test package.

- Fixed Rohlík finance-table OCR for positive amounts. ML Kit often splits `200 Kč` into separate `200` and `Kc/Kč` tokens; the parser now reconstructs the amount from the visual row instead of reading only rows that happened to stay as one OCR line.
- Added explicit support for OCR variants `Kč`, `Kc`, and `CZK`, and a guarded amount-column fallback that cannot confuse `Poznámka: ... 6:00-4 kola` with money.
- Removed the separate Compensation menu. Compensation-like entries are stored and displayed together with Bonuses; old `COMPENSATION` rows remain readable and are included in the Bonus totals for database compatibility.
- A low-order route can create an informational `Possible bonus / compensation` reminder, but no money is ever added automatically.
- `More -> Journal` is live and shows the latest audit events with timestamp, source, entity, old value and new value.
- `More -> Trash` is live for routes, bonus/penalty entries and advances. Items can be restored; permanent deletion is available after 30 days, and a daily WorkManager cleanup purges expired trash.
- `More -> Backups` can create a password-encrypted portable `.clbackup` archive and stage a restore. The archive contains the SQLCipher DB, photos, selected settings and the SQLCipher passphrase inside AES-256-GCM encryption so restoration to a new phone is possible. The DB key is re-wrapped with the new phone's Android Keystore on next cold start.
- Automatic local backups remain scheduled daily and the UI lists recent local backup files.
- `More -> Developer mode` now exposes 6-digit PIN setup/login and biometric authentication primitives in the actual UI; unlock state is session-only.
- Added parser regression tests for a split positive `200 Kc` penalty and compensation-to-bonus classification.

### v1.9 device QA

1. Re-import the supplied Rohlík bonus/penalty screenshot and verify `125 / 200 / 250 / 1500 Kč` are prefilled automatically, not only `0 Kč`.
2. Verify there is no separate Compensation item in `More`; compensation context should appear under `Bonuses / compensation`.
3. Delete a test route or bonus, open `More -> Trash`, restore it, and confirm it reappears.
4. Open `More -> Journal` and verify create/edit/delete/restore actions are visible.
5. Create a portable backup with a password. For restore testing, select it, enter the same password, stage restore, then fully close/reopen the app.
6. Set a 6-digit developer PIN and test both PIN and biometric unlock.


## Что изменено в v0.9

- Исправлен редкий случай переноса адреса OCR на две строки: например `Jana Zajice` + `12/25, Praha 17000`. Строка `Jana Zajice` теперь остаётся частью адреса, а не ошибочно становится ФИО клиента.

- Для `Заказников` OCR теперь использует **координаты строк ML Kit**, а не только плоский текст. Это нужно для экрана Rohlík, где `Dýško:` находится слева, а сумма чаевых — отдельным текстовым блоком справа на той же строке.
- Аналогично `Tašky:` сопоставляются со значением/формулой пакетов на той же визуальной строке.
- Исходный OCR в режиме заказников теперь скрыт по умолчанию. Он остается доступен по кнопке `Показать исходный OCR` только для диагностики; в БД сохраняются карточки клиентов, а не весь текст экрана.
- При выборе нескольких фотографий каждая фотография разбирается отдельно с сохранением геометрии текста, затем найденные клиенты объединяются в общий список.
- Повторно выбранные фотографии специально OCR-ятся заново, потому что старый кэш v0.5 не содержал координат строк.

Offline-first Android application foundation for courier shifts, routes, orders, earnings, OCR, reconciliation and encrypted local storage.

## v1.2 additions — saved-customer editing, shifts history and final reconciliation

- Saved customer/order records are now editable after OCR from both route details and `More -> Clients`: first name, last name, address, packages and tips.
- Every edit is written to the audit log as `ORDER_EDIT` with old/new values and `USER_CORRECTION` source.
- Changing an address safely detaches the order from its old merge group, cleans up one-member auto-groups, re-normalizes the address and reruns exact-address merge. Factual order count and route money stay derived/reactive.
- `More -> Shifts` is now a real screen with total shifts, complete/partial counts, total worked time, planned/completed rings and per-shift earnings.
- Each shift can be opened to see start/end time, worked duration, plan vs fact, routes, clients, factual orders, tips, comment and reconciliation results.
- Closing a shift now keeps the reconciliation result visible on Home (`Sverka passed` or detailed mismatches).
- Reconciliation checks plan/factual rings, missing customer-history data, route reported count vs raw customer cards, and courier cumulative-statistics delta when two snapshots exist.
- The small RÚIAN warning styling from v1.1 remains: dark burgundy triangle, normal text color.

### v1.2 device QA

1. Open a saved route, edit a customer's tip and verify route total changes immediately.
2. Edit an address that was part of a merge group and verify the group/factual-order count recalculates correctly.
3. Open `More -> Shifts`, open a closed shift and verify time, rings, routes and money.
4. Close a new shift and verify the reconciliation card remains visible on Home.


## Stack
- Kotlin 2.2.21
- Jetpack Compose + Material 3
- Room 2.8.0
- SQLCipher for Android 4.17.0
- ML Kit Text Recognition (bundled/offline model)
- WorkManager
- Android Keystore + AES-GCM
- BiometricPrompt + 6-digit PIN primitives

## Architecture
`ui -> repository/domain -> Room DAO -> SQLCipher`

OCR is intentionally split into two layers:
1. `OcrEngine`: real image-to-text recognition via bundled ML Kit.
2. `OcrParsers`: separate parsers for Route / Customers / Courier Statistics / Finance.

This prevents a universal scanner from silently guessing screen semantics.

## Important domain guarantees already encoded
- Money is stored as integer hellers, never Float/Double.
- Region = 2 rings + fixed 250 CZK bonus.
- OT/Express = 1 ring.
- Rate is applied to factual orders, not rings.
- Friday/Saturday/Sunday default rate = 80 CZK/order; other days = 50 CZK/order.
- Same normalized address inside one route is reversibly merged through `MergeGroup`.
- Original customer/order records are preserved.
- Photo duplicates are detected by SHA-256.
- Audit log is append-only through application DAO usage.
- Database passphrase is random and wrapped by Android Keystore.
- Portable backup primitive uses PBKDF2-HMAC-SHA256 + AES-256-GCM.

## What this version implements end-to-end
- encrypted DB and schema for the core entities from the specification;
- start/close shift;
- route creation with manually selected OT / Region / Express;
- camera/gallery acquisition;
- real offline OCR;
- editable OCR text;
- route OCR parser;
- customer/order OCR parser infrastructure;
- statistics snapshot OCR;
- bonus/penalty/compensation OCR;
- address normalization;
- reversible address merge;
- route/shift reconciliation engine;
- earnings primitives;
- audit log persistence;
- source photo + OCR result persistence;
- duplicate photo detection;
- scheduled local backup worker;
- portable encrypted backup manager;
- Compose shell with Home / Calendar / Statistics / Scanner / More;
- PIN hashing and biometric gate primitives.

## Remaining before the final v2.0 package

The next and final source package is reserved for the remaining specification pass: full cross-module analytics/reconciliation, stricter developer-mode protection of critical edits, photo-retention/pagination hardening, final backup/restore QA, notification lifecycle polish, visual cleanup/icon work, migration/instrumentation tests and end-to-end device stabilization.

## Build on Windows
Recommended: current stable Android Studio. Install Android SDK Platform 36 and Build Tools. Open this folder as a project and let Gradle sync.

If Android Studio reports that the project has no Gradle wrapper, use its built-in Gradle wrapper generation or run `gradle wrapper --gradle-version 8.13` once from a local Gradle 8.13 installation. The project intentionally pins AGP 8.13.2.

## Fastest phone testing workflow
Use USB + ADB. It is much faster than sending APK files through Telegram.

1. On Xiaomi/HyperOS: enable Developer options, USB debugging, and if requested "Install via USB".
2. Connect phone by cable and accept the RSA debugging prompt.
3. In Android Studio choose the Redmi device in the target selector.
4. Press Run. Android Studio builds and installs the debug APK directly.

After the first cable setup, Wireless debugging can be paired from Android Studio, so most later builds can be installed over Wi-Fi without Telegram or manually moving files.

For command-line reinstall once Gradle is available:
`adb install -r app/build/outputs/apk/debug/app-debug.apk`

`-r` keeps the existing application data during development.

## v0.2.0 — рабочий цикл трассы

Изменения относительно первого каркаса:

- исправлен запуск камеры: runtime-разрешение CAMERA, безопасный FileProvider и обработка отмены/ошибок;
- фотографии из галереи копируются во внутреннее хранилище приложения, чтобы OCR и история не зависели от временного URI;
- добавлен рабочий сценарий `Главная -> начать смену -> Сканер -> Трасса -> камера/галерея -> OCR -> проверка -> OT/Region/Express -> сохранить`;
- закрытые трассы появляются на главной и учитываются как 1/2 колечка;
- при недовыполнении плана закрытие смены требует свободный комментарий;
- для заказников можно выбрать несколько фотографий сразу;
- при переходе в заказники автоматически подставляется последняя трасса активной смены;
- после сохранения заказников выполняется обратимое объединение одинаковых нормализованных адресов;
- интерфейс главной и сканера переработан из технической заготовки в более пригодный для ежедневного тестирования вариант.

### Что проверить на реальном телефоне в первую очередь

1. Первый запуск камеры должен показать системный запрос разрешения и после разрешения открыть камеру.
2. Сделать фото любого текста, подтвердить снимок и проверить, что появляется OCR-текст.
3. Начать смену на главной, отсканировать трассу, вручную выбрать OT/Region/Express и сохранить.
4. Убедиться, что трасса появилась на главной и счётчик колечек изменился; Region должен дать 2 колечка.
5. Открыть `Сканер -> Заказники`, выбрать несколько фото и проверить распознавание списка.

OCR заказников пока требует настройки по реальным скриншотам интерфейса курьера. Инфраструктура распознавания настоящая; parser не подменяется фиктивными данными.

## v0.3.0 — заказники, реальные деньги и Liboc

- исправлено каноническое название склада: `Liboc` вместо `Libeň`;
- старое тестовое значение `LIBEN` из базы v0.2 безопасно читается как `LIBOC`, поэтому обновление приложения не должно ломать уже сохранённые тестовые записи;
- OCR трассы распознаёт `Liboc`, при этом оставлена совместимость со старым ошибочным вариантом текста;
- заказники после OCR теперь превращаются в отдельные редактируемые карточки клиентов;
- перед сохранением можно исправить имя, фамилию, адрес, количество пакетов и чаевые каждого клиента;
- клиента можно удалить из результата OCR или добавить вручную;
- последняя закрытая трасса активной смены автоматически подставляется для заказников;
- после сохранения выполняется нормализация адресов и обратимое auto-merge одинаковых адресов;
- сразу после импорта показывается число клиентов, фактических заказов, merge-групп, чаевых и рассчитанный заработок трассы;
- главная теперь реагирует на сохранение заказников и показывает живую сумму по обработанным трассам;
- карточка трассы показывает клиентов -> фактические заказы, merge-группы, базовую оплату, Region-бонус и чаевые;
- расчёт по-прежнему строится из первичных данных, а не хранится отдельной "магической" суммой.

### Следующий приоритет

Следующий рабочий проход: экран деталей трассы с просмотром/разделением merge-групп, полноценное редактирование уже сохранённых клиентов, затем закрытие смены + финальная сверка и накопительная статистика курьера.

## v0.4 fixes
- Fixed missing `RoundedCornerShape` import from v0.3.
- Multi-photo customer import now reuses OCR text for previously scanned duplicate photos instead of silently skipping them.
- Multi-photo import reports how many images were recognized/reused and how many customer cards were parsed.
- Customer save button is no longer silently disabled: validation now explains exactly what is missing.
- If route ID is not set, customer saving automatically resolves the latest route from the current work session.
- The home earnings card keeps showing the latest shift/day after the shift is closed instead of immediately dropping to zero.
- Warehouse naming remains canonical: Liboc / CH / HP.

## v0.5 fixes — Rohlík history parser + visible customer-save diagnostics
- Customer OCR is now tuned to the real Rohlík courier `Historie zakázek` layout shown in device photos.
- The parser uses stable labels `Cena`, `Dýško`, `Tašky`, `Typ platby` instead of treating every OCR line as a possible customer.
- Name and address are reconstructed from the lines immediately before `Cena`.
- `Dýško` supports Czech decimal amounts such as `30,00 Kč` and `50,00 Kč` and stores them in integer hellers.
- `Tašky` understands the Rohlík form `A(4) - C(5) - F(0) - (9)` and uses the total bag count; if the total is unreadable it falls back to the A/C/F sum.
- Multi-photo OCR still concatenates screens, while the Rohlík-specific parser separates individual customer cards.
- Customer saving now auto-resolves the latest route again directly inside the customer confirmation screen.
- Save failures/success are displayed immediately under the save button, so validation or database errors can no longer happen "silently" outside the visible scroll position.
- A partially invalid import is blocked with an explicit count of customer cards that still have no address, preventing accidental partial route calculations.

The exact OCR engine remains bundled ML Kit and offline. The screen-specific logic lives only in `OcrParsers`, so future Rohlík UI changes can be adapted without changing storage, calculations or the camera pipeline.
## v0.7 fixes — name/address extraction
- Address extraction for Rohlík history now explicitly rejects monetary rows (`Kč/CZK`) so `0,00 Kč` cannot become an address.
- The structured parser prefers the nearest line containing a Czech postal code before falling back to the weaker street-number heuristic.
- Name selection is biased to the same left-hand column as the address, avoiding right-side values and icons.
- Warning-triangle OCR artifacts such as leading `A Veronika ...` / `Δ Veronika ...` are stripped only in the Rohlík customer parser.
- Trailing chevron artifacts from the customer row are removed from names.

## v1.0 additions (RÚIAN + maps)

- Official Czech street validation using the ČÚZK RÚIAN `UI_ULICE` catalogue.
- The catalogue is downloaded from `https://services.cuzk.cz/sestavy/cis/UI_ULICE.zip`, reduced to a local normalized street-name index and then used offline.
- A first network-constrained background sync is scheduled automatically; monthly refreshes keep the local catalogue current.
- OCR no longer blindly moves a one-word customer name into a street. In ambiguous `name vs street` cases it only does so when RÚIAN confirms the longer street name and does not confirm the shorter alternative.
- Customer confirmation cards show whether the parsed street exists in RÚIAN. This validates the street name, not yet the exact house number/address point.
- `More -> Settings` now lets the user choose the default map provider: Google Maps (default), Waze, or Mapy.cz, and manually refresh the RÚIAN catalogue.
- `More -> Clients` shows stored customers/orders. Tapping an address opens it in the selected map provider.

The map integrations use public URL/deep-link mechanisms and do not require API keys for this launch-only use case.


## v1.1 additions — route details and reversible merge control

- Route cards on the Home screen are now tappable and open a real route detail screen.
- Route details show factual orders, clients, merge groups, base pay, Region bonus, tips and the live route total.
- Saved clients are shown inside the route, with packages and tips; tapping an address opens the map provider selected in Settings.
- Auto-merged addresses are shown as explicit `N clients -> 1 order` groups with the merge reason.
- Each merge group can be split manually, and all route merge groups can be split in one action.
- Splitting updates the orders table, so factual order count and route earnings recalculate automatically through the existing reactive Room/Flow pipeline.
- A manually split normalized address is remembered through the inactive merge-group history and is not silently auto-merged again on the next import.
- Auto-merge now safely attaches newly imported customers to an already active group for the same address instead of creating fragmented duplicate groups.
- The RÚIAN warning marker in customer confirmation is now a dark burgundy triangle while the warning text keeps the normal text color, making the warning visible without turning the whole message red.


## v1.3 additions — navigation, route editing and statistics

- Android system Back / gesture Back is now handled inside nested app pages instead of unexpectedly closing the app.
- Replaced the old `← Назад` text links in active nested pages with a compact dark-style back control/header.
- Route details now allow editing route type (OT / Region / Express), warehouse, reported order count and external route ID.
- Editing a route writes an audit-log correction and the reactive calculations immediately update ring count and route earnings.
- A route can be moved to the trash from its detail screen; this is a soft delete and does not irreversibly destroy linked customer/order data.
- Deleted routes are excluded from the normal customer list.
- Active shifts with no imported calendar plan no longer misleadingly look like a real `0/0` plan. A plan can be entered manually until calendar OCR/import is implemented.
- Added a functional Statistics tab with Day / Week / Month / All-time periods.
- Statistics derive orders, clients, rings, OT/Region/Express counts, route base pay, Region bonuses, tips, route gross, worked time and productivity metrics from stored primary data.
- Courier statistics snapshots are now observable in the Statistics tab.
- `Scanner -> Статистика курьера` keeps OCR editable: cumulative order count can be corrected manually before saving.
- Saving a statistics snapshot immediately compares it with the previous snapshot and with factual route orders completed between the two snapshot timestamps.
- Snapshot save and route edits are recorded in the audit log.

### Calendar direction

The calendar screenshots supplied from the courier portal are intentionally treated as a visual/structural reference for the next calendar pass: dark theme, month grid, warehouse/time color semantics, compact `time · K · warehouse` cells. v1.3 only fixes the zero-plan problem with a safe manual plan fallback; screenshot OCR/import and the redesigned month grid remain the next calendar-specific implementation step.

## v1.4 — calendar workflow

- Dark monthly calendar grid inspired by the courier portal while keeping CourierLedger's own visual language.
- Month navigation, today shortcut, warehouse/time color rules, and support for multiple planned blocks on one date.
- Manual day editing: start time, ring count, warehouse; empty save clears the day.
- Calendar OCR import from camera or screenshot gallery using the real bundled ML Kit result and line coordinates.
- Czech month recognition (Leden–Prosinec), day-cell geometry, Liboc/CH/HP parsing, and multiple blocks per day.
- Mandatory review screen before a month is written to the database; imported values stay editable.
- Calendar month import replaces only the confirmed month and records an audit entry.
- Shift start now sums all calendar blocks for that date instead of reading a single entry.
- Existing active/planned shifts automatically receive the calendar plan unless the user already set a manual override.
- Home screen shows today's imported plan before `Přihlásit se do fronty`.

## v1.6 — financial modules

- `More -> Bonuses`, `Compensations`, `Penalties`, `Advances` and `Diesel` are now functional instead of placeholders.
- Bonuses/compensations/penalties can be created manually, edited and soft-deleted to the trash; every meaningful change is written to the audit log.
- Penalties are stored as positive source amounts and subtracted only by the calculation engine; a 0 Kč penalty is valid and remains visible as an informational record.
- Compensation records remain manual: detecting a suspicious route never silently creates money.
- Advances are a separate module and reduce only the amount still expected to be paid; they are not treated as expenses or penalties.
- Diesel is configurable independently for each `YYYY-MM`; months without an override use the agreed default 3500 Kč.
- The Scanner finance flow is now editable after OCR: type, amount, date and description can all be corrected before saving.
- Monthly money calculation now includes actual route earnings rather than returning a zero route total.
- Statistics now show a current-month finance card with route gross, bonuses, compensations, penalties, diesel, accrued amount, net earnings, advances and remaining expected payout.
- All monetary input continues to be converted to integer hellers instead of being stored as floating-point values.

Next priorities: actual salary payments + payslip photo storage and salary reconciliation, order-count goal, compensation reminders, then backup/trash/developer-mode hardening.

## v1.6 — Salary, goals and Rohlík finance-table OCR

- Added real `Зарплата` module: actual payments, pay period, comment and saved payslip photo.
- Added independent reconciliation: app-expected payout vs actual received amount; calculations are never adjusted to match the payslip.
- Added `Цели` module for monthly order targets, completed/remaining orders, remaining planned workdays and required orders/day.
- Added `Импортировать с фото` directly inside Bonuses / Compensations / Penalties.
- Reworked finance OCR for the Rohlík `Datum / Částka / Položka / Poznámka` table using ML Kit line coordinates.
- One screenshot can now produce multiple editable financial rows. 0 Kč penalties are preserved.
- Classification recognises typical Rohlík penalty rows (`Prodlení`, `zpoždění`, `Hodnota nákupu`) and positive rows (`Regiony`, `Převoz`, bonus/compensation keywords). Unknown rows remain editable before saving.

## v2.0.0 — consistency + final analytics/UX pass

- Deleted routes are now excluded immediately from Home, Statistics, Shifts, Clients, reconciliation and money calculations until restored from Trash. Route-linked reminders are dismissed when a route is trashed.
- Statistics reacts live to route deletion/restoration and supports a custom date range with a month calendar and month/year selector.
- Financial summary follows the selected statistics period; diesel is prorated for partial months.
- Calendar cells are compact/adaptive for 5/6-week months so import actions remain reachable; redundant explanatory text in the day dialog was removed.
- Clients are grouped by date and then by route/kolo, with route type and warehouse shown before the clients.
- Bottom navigation icons for Calendar, Statistics and Scanner were replaced with proper Material icons; Home and More keep the existing visual language.
- Shifts screen now refreshes immediately when routes are trashed/restored.

## v2.1 additions

- Deleted routes are excluded at DAO level from routes, orders, customers, shift history and statistics until restored. Route deletion also soft-deletes its child orders with the same trash timestamp; restoring the route restores only those child orders.
- Individual customers/orders can be moved to Trash and restored. Delete is gated by Developer Mode in the UI.
- Whole shifts can be moved to Trash/restored; route/order children follow the shift transaction. Closed shift start/end time can be edited only in Developer Mode.
- Bottom navigation now always targets the top-level destination; tapping More while already inside a More subpage returns to the More root.
- Settings include the default warehouse, home address and vehicle diesel consumption (L/100 km).
- Home street is validated against the offline RUIAN street index. Driving distance to Liboc, Chrastany and Horni Pocernice is cached; calculation uses the actual warehouse scheduled/used for each workday rather than blindly using the default warehouse.
- Automatic monthly diesel estimate = sum(round-trip km for workdays) * vehicle L/100 km / 100 * last known diesel price. A manual monthly fuel override still has priority.
- Diesel price refresh only attempts the official public CEPRO/EuroOil web source and caches the last successfully observed average. There is no undocumented third-party fallback masquerading as an official value. If the official public page exposes no machine-readable prices, the last known price remains in use and manual override is available.
- Statistics has a custom date range plus an analytics dropdown for highest/lowest earnings, longest/shortest shift, most/least orders, largest customer tip, top tip day and top tip month.
- Customer list remains grouped by date -> route/ring -> customers. Deleted routes/shifts/orders cannot appear in it.
- Calendar informational filler under a date was removed and the grid is compacted for six-week months so import controls stay reachable.
- Calendar/Statistics/Scanner bottom icons were replaced with Material rounded icons.

## KurierX Online Licensing v1 (2.3.0)

This build includes Firebase Authentication/Firestore licensing, one-device activation keys, realtime freeze/revoke behavior and an OWNER-only KurierX Control panel. See `FIREBASE_FINAL_SETUP.txt` for the one remaining local step: copy your already-downloaded `google-services.json` into `app/`.
