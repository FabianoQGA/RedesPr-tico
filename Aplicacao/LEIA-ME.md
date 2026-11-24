# RedeLab

Cliente Swing e servidor Java para demonstrar transferência confiável por TCP e medição de latência por UDP. O JAR compila para Java 17 e dispensa bibliotecas externas.

## Executar

Abra dois terminais nesta pasta. Para teste na mesma máquina, inicie o servidor com:

~~~powershell
java -jar RedeLab.jar servidor 127.0.0.1 5000 recebidos
~~~

Abra o cliente no outro terminal:

~~~powershell
java -jar RedeLab.jar cliente
~~~

Mantenha o destino `127.0.0.1` e a porta `5000`. Selecione `amostra-redelab.txt` e clique em **Executar em paralelo**. O servidor confirma o SHA-256 após salvar o arquivo em `recebidos`. O registro do cliente mostra a confirmação TCP, o RTT de cada resposta UDP e a quantidade de sondas sem resposta.

Para o laboratório com roteadores, use `java -jar RedeLab.jar servidor 0.0.0.0 5000 recebidos` no PC2. A interface do cliente no PC1 recebe o endereço e a porta previstos na configuração da rede. O servidor usa a mesma porta nos dois transportes. Configure os redirecionamentos e, se necessário, o firewall do PC2 para TCP e UDP.

## Demonstração pelo terminal

Com o servidor em execução, use:

~~~powershell
java -jar RedeLab.jar demo 127.0.0.1 5000 amostra-redelab.txt 4 20
~~~

O comando envia o arquivo quatro vezes via TCP enquanto dispara 20 sondas via UDP. Também há modos `enviar` e `sondar` para uso separado. Consulte os parâmetros com `java -jar RedeLab.jar ajuda`.

Para provocar perdas UDP reproduzíveis no teste local, adicione `4` ao fim do comando do servidor. Isso descarta cada quarta sonda em cada série. O cliente registra a ausência de resposta após até 700 ms e segue sem retransmitir a sonda.

## Implementação

- TCP abre uma conexão por arquivo. O quadro contém a identificação do protocolo, o nome em UTF-8, o tamanho, o conteúdo e o SHA-256. O servidor lê a quantidade exata de bytes e envia um ACK de aplicação depois de validar e salvar. O limite por arquivo é de 100 MiB.
- UDP envia sondas de 17 bytes que carregam um identificador aleatório da sessão e um número de sequência. O servidor ecoa esses identificadores e o cliente calcula o RTT com um relógio monotônico. Um tempo-limite identifica sondas sem resposta.
- O servidor roda o receptor UDP em uma thread própria e distribui as conexões TCP a um conjunto de trabalhadores. O Swing usa um `SwingWorker` por operação, mantendo a interface disponível enquanto TCP e UDP estão ativos.

## Compilar e verificar

Instale um JDK 17 ou superior. Em PowerShell, execute `.\compilar.ps1 -Verificar`. O teste automatizado abre duas conexões TCP, verifica byte a byte os arquivos recebidos, mede seis sondas UDP enquanto uma transferência TCP continua pendente e testa a rejeição de um hash incorreto.

No Linux ou macOS, compile com `javac -encoding UTF-8 --release 17 -d classes src/*.java` e empacote com `jar --create --file RedeLab.jar --main-class RedeLab -C classes .`.
