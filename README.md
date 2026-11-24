# RedesPrático

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

## Fluxo de dados


