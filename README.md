# RedesPrático

## URL do Repositório

https://github.com/FabianoQGA/RedesPr-tico

## Rodar código

**Compilar em ambos hosts, de cliente e servidor:**

Ir para diretório Trabalho_Prático/Aplicacao e digitar:

New-Item -ItemType Directory -Force classes

$fontes = Get-ChildItem .\src -Filter *.java | ForEach-Object { $_.FullName }

javac -encoding UTF-8 --release 17 -d .\classes $fontes

**Para executar:**

Ir para diretório Trabalho_Prático/Aplicacao e digitar:

Cliente:
java -cp classes RedeLab cliente

Servidor:
java -cp classes RedeLab servidor 0.0.0.0 5000 recebidos

## Testes modulares

ping 192.168.0.1                         # 1. gateway (Deco)

ping 172.16.0.67                         # 2. IP WAN do Deco

ping 172.16.0.1                          # 3. Huawei (R2)

ping 172.16.0.3                          # 4. PC2

tracert -d 172.16.0.3                    # mostra onde para

Test-NetConnection 172.16.0.3 -Port 5000 # 5. TCP (só funciona com o servidor rodando)

### Liberar firewalls

New-NetFirewallRule -DisplayName "RedeLab UDP 5000 in" -Direction Inbound -Protocol UDP -LocalPort 5000 -Action Allow -Profile Any

New-NetFirewallRule -DisplayName "RedeLab TCP 5000 in" -Direction Inbound -Protocol TCP -LocalPort 5000 -Action Allow -Profile Any

New-NetFirewallRule -DisplayName "ICMP ping in" -Protocol ICMPv4 -IcmpType 8 -Direction Inbound -Action Allow -Profile Any