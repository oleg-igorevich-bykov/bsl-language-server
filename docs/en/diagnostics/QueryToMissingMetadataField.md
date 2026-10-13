# Using a non-existent field or virtual table in the query (QueryToMissingMetadataField)

<!-- Блоки выше заполняются автоматически, не трогать -->
## Description

The diagnostic checks that **query fields exist in a table that is specified correctly**: an attribute, dimension,
resource, accounting flag, standard or common attribute, as well as the fields of register virtual tables
(`<Resource>Balance`, `<Resource>Turnover`, `Period`, etc.). It also reports the third part of a table name that is
neither a tabular section of the object nor a virtual table known to the platform.

The table structure is taken from the working copy configuration, so an attribute added in the working copy but not yet
merged into the main branch is considered existing.

Accessing a non-existent field causes a runtime error. If the table has a field with a similar name, the message
names up to three of the closest ones.

The diagnostic is conservative: it reports only where the source of the field is resolved unambiguously.
The following is not checked:

- fields of temporary tables and nested queries;
- references through an unknown alias and references without an alias;
- external data source tables;
- ordering (`ORDER BY`), totals (`TOTALS`) and indexing, which name result columns;
- path segments after the first one (`T.Counterparty.TIN` is checked by `Counterparty` only);
- queries built by string concatenation or passed through a variable.

A non-existent metadata object (`Catalog.NoSuchCatalog`) is reported by the
[QueryToMissingMetadata](QueryToMissingMetadata.md) diagnostic; fields of such an object are not reported.

With an empty configuration the diagnostic checks nothing.

### Limits

- Changes to metadata files of the working copy are picked up without restarting the server, both via MCP
  (`analyze_file`) and in LSP mode. Walking the metadata tree is not free (about 1.4 s on a BSP export), so it is
  checked no more often than every few seconds: an edit made right before the next analysis may become visible
  with that delay rather than instantly.
- Types registered from the configuration (hints, `hover`) stay as they were after the metadata is re-read until
  the server is restarted; this does not affect this diagnostic.
- Extension objects and attributes are taken into account the same way the main server configuration sees them.

## Examples

Reference to an attribute the catalog does not have:
```sdbl
SELECT
    T.NoSuchAttribute AS Attribute
FROM
    Catalog.Users AS T
```
Reference to a field the register virtual table does not have:
```sdbl
SELECT
    R.NoSuchField AS Field
FROM
    InformationRegister.CurrencyRates.SliceLast AS R
```
Reference to a non-existent virtual table:
```sdbl
SELECT
    R.Rate AS Rate
FROM
    InformationRegister.CurrencyRates.NoSuchVirtualTable AS R
```

Correct references (no issues):
```sdbl
SELECT
    T.Ref AS Ref,
    T.Description AS Name
FROM
    Catalog.Users AS T
```
```sdbl
SELECT
    X.Ref AS Ref,
    X.Anything AS Anything // a temporary table field is not checked
FROM
    TT AS X
```

## Sources
<!-- Необходимо указывать ссылки на все источники, из которых почерпнута информация для создания диагностики -->
<!-- Примеры источников

* Source: [Standard: Modules (RU)](https://its.1c.ru/db/v8std#content:456:hdoc)
* Useful information: [Refusal to use modal windows (RU)](https://its.1c.ru/db/metod8dev#content:5272:hdoc)
* Источник: [Cognitive complexity, ver. 1.4](https://www.sonarsource.com/docs/CognitiveComplexity.pdf) -->
- [Development standards. Working with queries (RU)](https://its.1c.ru/db/v8std#browse:13:-1:26:27)
