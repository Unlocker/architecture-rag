# Нормализация ответа tool для эталонов (demo-check.sh, DEMO.3): убирает то, что меняется между запусками seed, и
# делает порядок массивов стабильным.
#   - удаляются gid; все моменты времени (ключи на `At`: lastSeenAt, sourceFetchedAt, ...; validFrom, validTo);
#     contentHash (считается и от времени получения) и sourceVersion (у закрытой записи зависит от того, успел ли
#     polling адаптера принести tombstone раньше snapshot). Порог staleAfter и флаги stale остаются;
#   - строки, равные gid известного узла ответа, заменяются на «Метка:имя», чтобы пути и связи читались без gid;
#     gid и моменты времени внутри текстов (warnings: «stale: Service <gid> lastSeenAt=<время>») заменяются так же;
#   - массивы объектов и массивы строк (affected) сортируются; порядок узлов в пути (paths[].nodes) сохраняется.
# Ключ сортировки не зависит от порядка полей в JSON сервера: поля упорядочены по имени.
def canon:
  if type == "object" then to_entries | sort_by(.key) | map({key, value: (.value | canon)}) | from_entries
  elif type == "array" then map(canon)
  else . end;
def label_of: (.label // .type // "asset");
def name_of: (.name // .hostname // .properties.name);
([.. | objects | select((.gid | type) == "string" and name_of != null)
  | {key: .gid, value: "\(label_of):\(name_of)"}] | from_entries) as $names
| def dropped: . as $k | ["gid", "validFrom", "validTo", "contentHash", "sourceVersion"] | index($k) != null or ($k | test("At$"));
  def clean(k):
    if type == "object" then
      to_entries | map(select(.key | dropped | not) | .key as $key | .value |= clean($key)) | from_entries
    elif type == "array" then
      map(clean(k))
      | if length > 0 and all(.[]; type == "object") then sort_by(canon | tojson)
        elif length > 0 and all(.[]; type == "string") and k != "nodes" then sort
        else . end
    elif type == "string" then
      ($names[.] // .)
      | gsub("(?<g>[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"; $names[.g] // "<gid>")
      | gsub("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z"; "<time>")
    else . end;
  clean(null)
