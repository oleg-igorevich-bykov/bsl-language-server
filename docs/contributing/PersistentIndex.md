# Сохранение индекса между сессиями (исследование)

> **Статус:** исследование, не реализовано. Документ фиксирует анализ кода на момент коммита
> `ecda0b190b6` (октябрь 2026) и служит отправной точкой, если к идее вернутся. Перед реализацией
> сверьте описанное с актуальным кодом: имена классов и методов могли измениться.

## Вопрос

Можно ли сохранять на диск индексы рабочей области, чтобы при следующем запуске не строить их
заново, и даст ли это заметное ускорение старта?

## Вывод

- Технически сохранение возможно, и для него есть естественная точка (см. раздел «Ключевое наблюдение»).
- Сохранять **только индекс ссылок** (`ReferenceIndex`) смысла нет: основная стоимость старта —
  разбор (ANTLR) каждого модуля, а загруженный индекс ссылок без дерева символов не отвечает ни на
  один запрос. Разбор всё равно пришлось бы выполнить.
- Ощутимый выигрыш даёт только **снимок состояния документа после наполнения**: дерево символов,
  вхождения ссылок, записи остальных переживающих наполнение индексов и замороженные метрики.
  Ключ снимка — хэш текста файла. Неизменившиеся файлы при этом не разбираются вовсе.
- Выигрыш получают режимы `lsp`, `websocket` и `mcp`. Режиму `analyze` снимок не поможет:
  диагностикам нужен AST каждого файла.
- Прежде чем реализовывать, нужно **измерить**, какую долю времени старта занимает разбор
  (см. раздел «Порядок работ»).

## Как сейчас наполняется рабочая область

`ServerContext.populateContext(Collection<File>)` параллельно обходит файлы и для каждого нового
документа под блокировкой записи выполняет:

1. `createDocumentContext(uri)`;
2. `rebuildDocument(documentContext)` → `DocumentContext.rebuildFromFileSystem()` → `rebuild(content, 0)`:
   чтение файла, отпечаток SHA-256 содержимого, токенизация и разбор (`BSLTokenizer`), построение
   `SymbolTree` (`SymbolTreeComputer`);
3. после `rebuild` аспект `EventPublisherAspect` публикует `DocumentContextContentChangedEvent`,
   и синхронно отрабатывают его слушатели (см. ниже);
4. `documentContext.freezeComputedData()` — метрики, сложность и данные игнорирования диагностик
   переживут очистку;
5. `tryClearDocument(documentContext)` → `clearSecondaryData()`: выбрасываются текст, токены, AST,
   запросы, тип модуля, диагностики. Публикуется `ServerContextDocumentClearedEvent`.

После наполнения публикуется `ServerContextPopulatedEvent`. На него:

- `SymbolTypeIndex.handleServerContextPopulated` пересобирает объявленные типы возвращаемых значений
  по всей области — **без разбора**, только по деревьям символов;
- `MethodReturnTypeIndexer.handleServerContextPopulated` запускает межмодульный проход до
  неподвижной точки, доразрешая то, что не разрешилось из-за параллельного порядка разбора.
  Этот проход **снова разбирает** часть документов (`withDocumentsLoaded`, `recomputeLoading` →
  `ServerContext.rebuildDocument` … `tryClearDocument`).

Итого стоимость старта складывается из:

- чтения метаданных конфигурации (mdclasses) и обхода файлов для `MetadataFingerprint`;
- чтения и **разбора каждого модуля**;
- работы слушателей `DocumentContextContentChangedEvent`;
- прохода `MethodReturnTypeIndexer` после наполнения, в том числе повторных разборов.

## Ключевое наблюдение

`clearSecondaryData()` **не трогает `SymbolTree`**, а замороженные метрики сохраняет. Поэтому после
наполнения каждый не открытый в редакторе документ находится в состоянии `WITHOUT_CONTENT`:
AST нет, а дерево символов, метрики и записи переживших очистку индексов есть. Именно это
состояние и стоит сохранять и восстанавливать: оно уже доказанно достаточно для работы сервера,
пока документ не открыт.

## Инвентаризация индексов

### Слушатели `DocumentContextContentChangedEvent`

| Слушатель | Что делает | Порядок |
|---|---|---|
| `ConfigurationModuleMembersProvider` | регистрирует члены модуля конфигурации в `TypeRegistry`, наполняет `moduleTypeRefByUri` | 100 |
| `OScriptModuleMembersProvider` | то же для `.os`, сброс членов подтипов | 100 |
| `ReferenceIndexFiller` | обход AST → вхождения в `ReferenceIndex` | 200 |
| `SymbolTypeIndex` | объявленные типы методов и параметров | 200 |
| `MethodReturnTypeIndexer` | выведенные по телу типы возврата, граф зависимостей документов | 300 |
| `WorkspaceSymbolIndex` | снимки символов для `workspace/symbol` | — |
| `AnnotationReferenceFinder` | аннотации `.os` в `AnnotationRepository` | — |
| `AbstractOScriptLazyIndex` (наследники) | ленивые индексы OneScript | — |
| `OScriptMetaAnnotationResolver` | сброс кэша замыкания ролей аннотаций | — |
| `DocumentContextLazyDataMeasurer` | замеры (только при `app.measures.enabled`) | последний |

Кроме того, базовый `AbstractDocumentLifecycleClearableIndex` подписывает на это событие и на
события очистки, закрытия и удаления документа своих наследников: `ControlFlowGraphIndex`,
`AssignmentByReceiverIndex`, `CallStatementByReceiverIndex`, `EventContractsIndex`,
`InferredExpressionTypeIndex`, `PropertyMethodCallIndex`, `GuardConditionNarrowing`,
`VariableFlowAnalyzer`, а также `MethodReturnTypeIndexer` и `WorkspaceSymbolIndex`.

### Что переживает наполнение и потому требует сохранения

Наследники `AbstractDocumentLifecycleClearableIndex` по умолчанию очищаются на
`ServerContextDocumentClearedEvent`, то есть после наполнения они пусты. Это ленивые кэши, они
наполнятся при открытии документа, и сохранять их **не нужно**. Очистку переопределяют
только `MethodReturnTypeIndexer` («записи остаются в силе») и `WorkspaceSymbolIndex` (no-op).

| Данные | Где живут | Сериализуемость | От чего зависят |
|---|---|---|---|
| Вхождения ссылок | `SymbolOccurrenceRepository` (символ → отсортированный массив), `LocationRepository` (URI → массив) | Просто: `Symbol` — record `(mdoRef, moduleType, scopeName, symbolKind, symbolName)`, вхождение — тип, символ, URI и 4 координаты | Текст файла; метаданные (`findCommonModule`, `getConfiguration`); настройка `references.commonModuleAccessors`; `OScriptLibraryIndex`; self-тип модуля из `TypeRegistry`. **Текст других модулей не влияет** |
| Дерево символов | `DocumentContext.symbolTree` | Средне: дерево с ссылками `parent`/`children` и `owner = DocumentContext`. AST в символах не хранится; описания (`MethodDescription`, `VariableDescription`, `ParameterDescription` из `bsl-parser`) — чистые данные: строки, диапазоны, списки, без токенов ANTLR | Текст файла; `SelfMemberClassifier` и `EventHandlerClassifier` (метаданные и типы) |
| Метрики, сложность, игнорирование диагностик | `DocumentContext` (замороженные `Lazy`) | Просто | Текст файла |
| `WorkspaceSymbolIndex` | автономные снимки (имя, вид, диапазон, теги, контейнер) | Просто | Дерево символов |
| `SymbolTypeIndex` | ключ — объект `MethodSymbol` | Сохранять не обязательно: пересобирается по деревьям символов без разбора на `ServerContextPopulatedEvent` | Деревья символов, `TypeRegistry` |
| `MethodReturnTypeIndexer` | `MethodSymbol` → типы; `methodsByUri`, `dependentsByUri`, `dependenciesByUri`; служебные `pending`/`provisional`/`indexed` | Средне: `TypeRef` — record `(TypeKind, qualifiedName)`, сохраняется по имени | **Межмодульно**: значение метода зависит от вызываемых методов других документов |
| Члены модулей | `TypeRegistry` (через `ConfigurationModuleMembersProvider`, `OScriptModuleMembersProvider`) | Средне | Дерево символов, метаданные |
| Аннотации `.os` | `AnnotationRepository` | Просто | Текст файла |
| Индексы OneScript | `OScriptLibraryIndex`, наследники `AbstractOScriptLazyIndex` | Требует отдельного разбора | Тексты `.os`, `lib.config`, структура каталогов библиотек |

## Почему одного `ReferenceIndex` недостаточно

1. `ReferenceIndex.buildReference` на каждое вхождение вызывает `getSymbolTree()`:
   - у документа-цели (`getSourceDefinedSymbol` → `serverContext.getDocument(mdoRef, moduleType)`);
   - у документа-источника (`getFromSymbol` → `getDocumentNoLock(uri)` … `orElseThrow()`).
   
   Без деревьев символов индекс не может построить ни одного `Reference`.
2. `ReferenceIndexFiller.fill()` — это лишь обход уже готового AST. Если его пропустить, разбор и
   остальные слушатели всё равно отработают, поэтому сэкономится малая доля времени.

## Предлагаемая схема

### Ключи инвалидации

- **Документ:** SHA-256 текста. Он уже вычисляется в `DocumentContext.contentFingerprint` при
  каждом `rebuild`. Сравнение по `mtime`/размеру дешевле, но ненадёжно (см. ограничение
  `MetadataFingerprint`); читать файл всё равно дёшево по сравнению с разбором.
- **Рабочая область целиком** (при несовпадении снимок выбрасывается полностью):
  - отпечаток метаданных (`MetadataFingerprint.of(root)`) — с его известным ограничением по
    `mtime` и размеру;
  - хэш настроек `LanguageServerConfiguration`, влияющих на индексацию (как минимум
    `references.commonModuleAccessors`, исключаемые пути, язык);
  - состав и отпечаток библиотек OneScript;
  - версия BSL LS и версия формата снимка.

### Поток загрузки

1. Прочитать заголовок снимка и сверить ключи рабочей области. Не совпали → обычное наполнение.
2. В `populateContext` для каждого файла: прочитать текст, посчитать SHA-256.
   - Хэш совпал → **восстановить** документ в состояние `WITHOUT_CONTENT`: дерево символов
     (владелец — новый `DocumentContext`), замороженные метрики, записи индексов.
   - Не совпал или документа нет в снимке → обычный `rebuildDocument`.
3. Восстановление **не должно** публиковать `DocumentContextContentChangedEvent`: слушатели
   ожидают AST, и `ReferenceIndexFiller.fill()` упадёт с `DocumentContentUnavailableException`
   на `getAst()`. Нужен отдельный путь, например:
   - интерфейс `PersistableDocumentIndex` с методами вида `export(uri)` / `restore(uri, data)`, или
   - отдельное событие восстановления документа, на которое подписываются сохраняемые индексы.
4. После наполнения — обычный `ServerContextPopulatedEvent`. `SymbolTypeIndex` пересоберёт
   объявленные типы по деревьям символов (это дёшево). Для `MethodReturnTypeIndexer` — см. ниже.

### Межмодульные выведенные типы

Значение `MethodReturnTypeIndexer` для метода зависит от методов других документов, поэтому его
можно восстановить только вместе с инвалидацией:

- изменившиеся документы и их транзитивно зависимые (по `dependentsByUri`) помечаются как
  изменившиеся;
- дальше работает существующий механизм `propagate` / проход после наполнения.

На первой итерации выведенные типы можно не сохранять, но тогда проход после наполнения снова
разберёт документы, и часть выигрыша пропадёт.

### Формат и размещение

Рекомендуется **собственный бинарный файл на рабочую область**, а не существующий EhCache:

- таблица строк (mdoRef, имена, URI) плюс секции по документам, ключ секции — URI и SHA-256 текста;
- запись во временный файл и атомарное переименование;
- запись после наполнения и затем с задержкой после изменений, асинхронно. Полагаться только на
  завершение процесса нельзя: клиент может завершить сервер принудительно.

Почему не EhCache (`infrastructure/CacheConfiguration`):

- общий лимит диска `DISK_SIZE_MB = 50` на все кэши;
- каталог кэша считает `CachePathProvider` по md5 **текущего каталога процесса**, а не рабочей
  области: при нескольких рабочих областях снимки перемешаются;
- для сложных значений нужна Java-сериализация или собственные `Serializer`'ы;
- модель «ключ → значение» неудобна для снимка, который восстанавливается целиком.

Размер снимка для крупной конфигурации — порядка десятков мегабайт. Это прикидка по числу
вхождений (на крупных проектах их миллионы, по несколько байт на вхождение при плотном
кодировании), а не замер.

## Риски и ограничения

- **Сопровождение — главный риск.** Каждый новый индекс, наполняемый по документу и
  переживающий его очистку, обязан попасть в снимок. Иначе после восстановления его данных
  просто не будет, причём без какой-либо ошибки. Нужны два теста:
  - все бины, слушающие `DocumentContextContentChangedEvent` и переживающие
    `ServerContextDocumentClearedEvent`, поддерживают сохранение или явно перечислены в
    разрешённом списке;
  - эквивалентность: индексы и ответы LSP после восстановления совпадают с построенными
    заново на тестовых фикстурах.
- **Идентичность символов.** `equals` у `AbstractMethodSymbol` включает `owner`
  (`DocumentContext`), поэтому символы восстанавливаются с новым владельцем. Индексы, где ключ —
  `MethodSymbol`, наполняются только после восстановления деревьев.
- **Порядок восстановления.** Слушатели сейчас упорядочены через `@Order` (регистрация членов
  модулей → индекс ссылок и объявленные типы → выведенные типы). Восстановление должно
  соблюдать тот же порядок: например, проход self-членов в индексе ссылок зависит от
  `moduleTypeRefByUri`. `TypeRegistry` (типы конфигурации) должен быть наполнен до восстановления.
- **`analyze` не ускоряется**: диагностикам нужен AST каждого файла. Кэширование результатов
  диагностик — отдельная задача, и рискованная: диагностики зависят от других документов.
- **Метаданные не ускоряются.** Чтение конфигурации mdclasses и обход файлов для
  `MetadataFingerprint` (на выгрузке БСП 3.1.12 — около 1,4 с только на обход) остаются.
- **Несколько экземпляров сервера** на одну рабочую область: нужна блокировка файла снимка
  либо каталоги с суффиксом `@N`, как у EhCache.
- **Снимок — только ускорение.** Любая ошибка при чтении или несовместимый формат → полное
  наполнение заново, без падения сервера.
- **OneScript.** Индексы библиотек зависят от структуры каталогов и `lib.config`. На первых
  этапах файлы `.os` разумно всегда разбирать.

## Порядок работ

1. **Замер.** Разложить `populateContext` на крупной конфигурации (БСП, ERP) по фазам:
   метаданные, чтение файлов, разбор, `ReferenceIndexFiller.fill`, остальные слушатели, проход
   `MethodReturnTypeIndexer` после наполнения. Подойдут JFR или `MeasureCollector.measureIt`
   вокруг фаз; нынешний `DocumentContextLazyDataMeasurer` (см. [Замер производительности](Measures.md))
   сам разбор не измеряет. Если разбор и слушатели занимают меньше примерно половины времени
   старта, дальше идти не стоит.
2. **Инфраструктура и ядро:** формат снимка, ключи инвалидации, путь восстановления в
   `populateContext`, `SymbolTree`, замороженные метрики, `ReferenceIndex`, `WorkspaceSymbolIndex`,
   регистрация членов модулей конфигурации. Только BSL; `.os` разбираются.
   Плюс тест эквивалентности и тест полноты.
3. **Выведенные типы возврата** с инвалидацией по зависимым документам.
4. **OneScript:** `AnnotationRepository`, `OScriptModuleMembersProvider`, индексы библиотек.

## Ключевые места в коде

- `context/ServerContext` — `populateContext`, `rebuildDocument`, `tryClearDocument`,
  `refreshConfigurationIfStale`;
- `context/DocumentContext` — `rebuild`, `contentFingerprint`, `clearSecondaryData`,
  `freezeComputedData`;
- `context/MetadataFingerprint` — отпечаток файлов метаданных;
- `context/symbol/` — `SymbolTree`, `AbstractMethodSymbol`, `AbstractVariableSymbol`;
- `references/` — `ReferenceIndex`, `ReferenceIndexFiller`, `model/SymbolOccurrenceRepository`,
  `model/LocationRepository`, `model/AnnotationRepository`;
- `types/index/` — `SymbolTypeIndex`, `MethodReturnTypeIndexer`, `WorkspaceSymbolIndex`;
- `types/registry/` — `ConfigurationModuleMembersProvider`, `TypeRegistry`;
- `index/AbstractDocumentLifecycleClearableIndex` — базовая очистка индексов по событиям документа;
- `infrastructure/` — `CacheConfiguration`, `CachePathProvider` (существующий дисковый EhCache).
