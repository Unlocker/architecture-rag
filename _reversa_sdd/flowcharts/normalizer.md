# Блок-схемы: ingestion/normalizer

> Archaeologist, R.2c. Пути относительно `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/`.

## `Normalizer.normalize` (`Normalizer.java:54-86`) 🟢

```mermaid
flowchart TD
  A["normalize(event, raw)"] --> T{"type == architecture.asset.upserted.v1?"}
  T -- нет --> Q1["Quarantined UNSUPPORTED_EVENT_TYPE"]
  T -- да --> S{"source = urn:corp:eam|scm|cmdb|deploymap\nи есть маппер?"}
  S -- нет --> Q2["Quarantined UNKNOWN_SOURCE"]
  S -- да --> V{"dataschema = urn:corp:schema:asset-upserted:N\nи N ∈ supportedSchemaVersions?"}
  V -- нет --> Q3["Quarantined UNKNOWN_SCHEMA_VERSION"]
  V -- да --> R["SourceRecord(key, version, raw.hash, event.time, active=true)"]
  R --> M["mapper.map(Input, CommandSink)"]
  M -- NormalizationException --> Q4["Quarantined e.code / e.message"]
  M -- IllegalArgumentException --> Q5["Quarantined INVALID_PAYLOAD (текст фиксированный)"]
  M -- ok --> OK["Normalized(commands, unresolved, warnings)"]
  M -. другое RuntimeException .-> UP["пробрасывается вызывающему (NPE и т.п.)"]
```

## Добавление связи: `CommandSink.relation` / `link` (`CommandSink.java:53-102`) 🟢

```mermaid
flowchart TD
  R["relation(type, from, to, props, validity, field)"] --> O["other = (from == self) ? to : from"]
  O --> E{"resolver.exists(other)?"}
  E -- да --> C["commands += UpsertRelation(assertedBy = self)"]
  E -- нет --> U["unresolved += UnresolvedReference(self, field, type, other, relation)"]
  L["link(type, from, to, ...)"] --> F{"exists(from)?"}
  F -- нет --> U1["unresolved += (fromField, from)"]
  L --> G{"exists(to)?"}
  G -- нет --> U2["unresolved += (toField, to)"]
  F -- да --> B{"обе известны?"}
  G -- да --> B
  B -- да --> C2["commands += UpsertRelation"]
```

## EAM: выбор формата (`EamMapper.java:66-118`) 🟢

```mermaid
flowchart TD
  K{"sourceType"} -- "PLATFORM / SOLUTION" --> AO["mapArchObject (нужен attrs)"]
  K -- "IT_SYSTEM" --> H{"есть attrs-объект?"}
  H -- да --> AO
  H -- нет --> FL["плоский IT_SYSTEM: без eamCode, ownerTeam → OWNED_BY, dependsOn → warning"]
  K -- "TEAM" --> TM["UpsertNode(Team)"]
  K -- "SERVICE_DEPENDENCY" --> SD["link DEPENDS_ON SCM/SERVICE → SCM/SERVICE"]
  K -- иное --> X["UNKNOWN_SOURCE_TYPE"]
  AO --> AT{"attrs отсутствует?"}
  AT -- да --> MR["MISSING_REQUIRED_FIELD: attrs"]
```

## Deploy map: привязка Deployment к сервису (`DeployMapMapper.java:193-225`, `format = deploymap-poc-1`) 🟢

```mermaid
flowchart TD
  A["bindToRepository"] --> R["repo = lookup.repositoryByUrl(url)"]
  R --> D["DEPLOYED_FROM → repo или alias LookupKeys.repositoryUrl(url)"]
  D --> S["services = repo ? servicesImplementedIn(repo) : []"]
  S --> C1{"payload.service ∈ services?"}
  C1 -- да --> H1["HAS_DEPLOYMENT от declared"]
  C1 -- нет --> C2{"services.size == 1?"}
  C2 -- да --> H2["HAS_DEPLOYMENT от единственного"]
  C2 -- нет --> C3{"payload.service задан?"}
  C3 -- да --> H3["HAS_DEPLOYMENT от SCM/SERVICE/<service> (отложенная при неизвестном)"]
  C3 -- нет --> W["warning DEPLOYMENT_SERVICE_UNRESOLVED"]
```
