# Нормализация ответа tool для эталонов (demo-check.sh, DEMO.3): убирает то, что меняется между запусками seed, и
# делает порядок массивов стабильным.
#   - поля gid и все моменты времени (ключи на `At`: lastSeenAt, sourceFetchedAt, ...; validFrom, validTo) удаляются;
#     порог staleAfter и флаги stale остаются;
#   - строки, равные gid известного узла ответа, заменяются на «Метка:имя», чтобы пути и связи читались без gid;
#   - gid и моменты времени внутри текстов (warnings: «stale: Service <gid> lastSeenAt=<время>») заменяются так же;
#   - массивы объектов сортируются по JSON-представлению; порядок массивов строк (узлы пути) сохраняется.
def label_of: (.label // .type // "asset");
def name_of: (.name // .hostname // .properties.name);
([.. | objects | select((.gid | type) == "string" and name_of != null)
  | {key: .gid, value: "\(label_of):\(name_of)"}] | from_entries) as $names
| def clean:
    if type == "object" then
      with_entries(select(.key != "gid" and (.key | test("At$") | not) and .key != "validFrom" and .key != "validTo")
                   | .value |= clean)
    elif type == "array" then map(clean) | if all(.[]; type == "object") then sort_by(tojson) else . end
    elif type == "string" then
      ($names[.] // .)
      | gsub("(?<g>[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"; $names[.g] // "<gid>")
      | gsub("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z"; "<time>")
    else . end;
  clean
