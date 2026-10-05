#!/bin/sh
# Демо-PKI стенда: CA и серверные сертификаты для reverse-proxy и Bolt Neo4j в volume `certs`.
# Идемпотентен: если сертификаты уже есть, ничего не меняет (CA живёт, пока жив volume).
set -eu
cd /certs
if [ -f .ready ]; then
  echo "certs: already generated"
  exit 0
fi

san="DNS:localhost,DNS:reverse-proxy,DNS:neo4j,IP:127.0.0.1"

openssl req -x509 -newkey rsa:3072 -nodes -days 825 -subj "/CN=archrag-demo-ca" \
  -keyout ca.key -out ca.crt

issue() { # <каталог> <файл ключа> <файл сертификата>
  mkdir -p "$1"
  openssl req -newkey rsa:3072 -nodes -subj "/CN=localhost" -keyout "$1/$2" -out "$1/req.csr"
  printf 'subjectAltName=%s\nextendedKeyUsage=serverAuth\n' "$san" > "$1/ext.cnf"
  openssl x509 -req -in "$1/req.csr" -CA ca.crt -CAkey ca.key -CAcreateserial -days 825 \
    -extfile "$1/ext.cnf" -out "$1/$3"
  rm "$1/req.csr" "$1/ext.cnf"
}
issue proxy tls.key tls.crt
issue neo4j private.key public.crt

# Neo4j ssl policy требует каталог trusted (клиентские сертификаты не используются, client_auth=NONE).
mkdir -p neo4j/trusted neo4j/revoked
cp neo4j/public.crt neo4j/trusted/public.crt

# nginx читает ключ мастер-процессом (root); Neo4j работает как uid 7474.
chown -R 7474:7474 neo4j
chmod 600 neo4j/private.key
chmod 600 ca.key proxy/tls.key
# ca.crt и сертификаты публичны: smoke.sh забирает ca.crt из volume.
chmod 644 ca.crt proxy/tls.crt neo4j/public.crt
chmod 755 /certs proxy neo4j

touch .ready
echo "certs: generated"
