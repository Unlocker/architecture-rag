# gid-mapping-crosswalk: требования

> Use case модуля [`ingestion/identity-resolution`](../requirements.md). Алиасы `IR`, `MIG`, `IT` — как там. Общий дизайн: [`../design.md`](../design.md).

## Обзор
Выдача и поиск постоянного `gid` по ключу записи источника и ручное подтверждение соответствия двух записей (approved crosswalk) без слияния разных `gid` 🟢 (`IR/IdentityMapping.java:8-33`).

## Требования

| ID | Требование | MoSCoW | Evidence |
|----|-----------|--------|----------|
| UC1-01 | `resolve(key)` возвращает один и тот же `gid` при любом числе вызовов и конкуренции | Must | `IR/PostgresIdentityMapping.java:45-60,124-132` 🟢 |
| UC1-02 | `find(key)` пуст до первого `resolve`/`approve` и ничего не создаёт | Must | `:36-43,147-154` 🟢 |
| UC1-03 | `approve` назначает общий `gid`: существующий у одного из ключей, иначе новый | Must | `:104-118` 🟢 |
| UC1-04 | `approve` ключей с разными `gid` -> `CrosswalkConflictException`, ничего не меняется | Must | `:107-109,113-116` 🟢 |
| UC1-05 | `approve` идемпотентен и не зависит от порядка `left/right` | Must | `:84-86,136-137` 🟢 |
| UC1-06 | Аудит crosswalk (`approved_by`, `reason`, `approved_at`) обязателен и сохраняется | Must | `IR/Crosswalk.java:14-25`; `MIG/V2:22-24` 🟢 |
| UC1-07 | `keysOf(gid)` — все ключи, упорядочены | Should | `:62-78` 🟢 |

## Критерии приёмки

```gherkin
Сценарий 1 (позитивный): resolve
  Дано ключ SCM/CATALOG_SYSTEM/42 не отображён
  Когда вызван resolve, затем снова resolve на новом экземпляре PostgresIdentityMapping
  Тогда gid совпадает, find возвращает его
  # IdentityMappingIT.findIsEmptyUntilResolved, repeatedResolveReturnsSameGidWithNewMappingInstance

Сценарий 2 (негативный): одинаковый sourceId в разных системах
  Дано EAM/T/1 и SCM/T/1
  Когда оба resolve
  Тогда gid разные
  # sameSourceIdInDifferentSystemsIsNotSameEntity

Сценарий 3 (гонка): конкурентный resolve
  Дано N потоков вызывают resolve одного ключа
  Тогда все получают один gid
  # concurrentResolveConvergesToOneGid

Сценарий 4 (позитивный): crosswalk
  Дано EAM/IT_SYSTEM/PAY и SCM/CATALOG_SYSTEM/42 не отображены
  Когда approve(Crosswalk(left,right,"admin","same system",t))
  Тогда обоим ключам один gid, keysOf(gid) = оба ключа, одна строка approved_crosswalk
  # crosswalkGivesEamAndScmRecordsOneGid; crosswalkAdoptsGidOfAlreadyMappedKey (если один ключ уже имел gid, он берётся)

Сценарий 5 (идемпотентность): повторный approve в обратном порядке
  Когда approve(left,right) и approve(right,left)
  Тогда gid тот же, строка approved_crosswalk одна, первые approved_by/reason сохранены
  # crosswalkIsIdempotentAndOrderInsensitive

Сценарий 6 (негативный): разные gid
  Дано оба ключа уже отображены на разные gid
  Когда approve
  Тогда CrosswalkConflictException, mapping обоих ключей и approved_crosswalk не изменились
  # crosswalkOfKeysWithDifferentGidsIsRejectedAndChangesNothing

Сценарий 7 (негативный, валидация): некорректный Crosswalk
  Когда Crosswalk(key,key,...) или approvedBy/reason пусты
  Тогда IllegalArgumentException на конструкторе
  # CrosswalkTest.rejectsLinkToItself, rejectsBlankApprover, rejectsBlankReason

Сценарий 8 (гонка): resolve и approve параллельно
  Тогда итог — общий gid либо чистый CrosswalkConflictException
  # concurrentResolveAndApproveEndWithSharedGidOrCleanConflict
```

## Трассировка
`IR/PostgresIdentityMapping.java`, `IR/IdentityMapping.java`, `IR/Crosswalk.java`, `IR/CrosswalkConflictException.java`, `IR/SourceKeys.java`, `MIG/V2__identity_mapping.sql`. Тесты: `CrosswalkTest` (в модуле), `IT/IdentityMappingIT` (`platform/integration-tests`) 🟢.
