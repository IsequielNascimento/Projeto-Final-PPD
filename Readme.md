# Projeto Final — Sistema de Troca de Mensagens com Controle Offline

**Instituto Federal do Ceará — Engenharia de Computação**  
Disciplina: Programação Paralela e Distribuída — 2026.1
Prof. Cidcley T. de Souza

---

## Objetivo

Implementar um sistema de troca de mensagens com controle de mensagens offline, utilizando **RMI** para comunicação remota cliente–servidor e **JMS/ActiveMQ** como Middleware Orientado a Mensagens (MOM) para gerenciamento das filas de mensagens offline.

---

## Tecnologias Utilizadas

| Tecnologia | Papel no sistema |
|---|---|
| **Java RMI** | Comunicação remota entre cliente e servidor (Req. 4) |
| **RMI Callbacks** | Servidor envia mensagens/status ao cliente em tempo real (Req. 3) |
| **Apache ActiveMQ** | Broker MOM embutido; gerencia as filas JMS (Req. 5) |
| **JMS (Java Message Service)** | API para enfileirar e consumir mensagens offline (Req. 6/7) |
| **Java Swing** | Interface gráfica |

---

## Arquitetura

O sistema segue separação total entre **lógica de negócio** e **interface gráfica**.

```
┌─────────────────────────────────────────────────────┐
│                     LÓGICA (PPD)                    │
│                                                     │
│  Config.java          — constantes globais          │
│  ClienteCallback.java — interface RMI callback      │
│  ServicoMensagens.java— interface RMI do serviço    │
│  ServidorLogica.java  — broker MOM + RMI + roteamento│
│  ClienteLogica.java   — stub RMI + callback export  │
└─────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────┐
│                       UI (Swing)                    │
│                                                     │
│  ServidorUI.java  — janela do servidor (log)        │
│  ClienteUI.java   — janela do cliente (chat)        │
│  Launcher.java    — janela inicial                  │
└─────────────────────────────────────────────────────┘
```

### Fluxo de comunicação

```
                    ┌──────────────────────────────────┐
                    │         ServidorLogica            │
                    │                                  │
                    │  ┌────────────┐  ┌────────────┐  │
  Cliente A ──RMI──▶│  │ ServicoImpl│  │   Broker   │  │
                    │  │  (RMI)     │  │ (ActiveMQ) │  │
  Cliente B ──RMI──▶│  └─────┬──────┘  └─────┬──────┘  │
                    │        │               │         │
                    └────────┼───────────────┼─────────┘
                             │               │
              B online?      │               │
              ┌──────────────┴──────┐        │ B offline?
              ▼                     ▼        ▼
    callback RMI direto      enfileira na Queue JMS de B
    (entrega instantânea)    (entregue quando B voltar)
```

---

## Requisitos Atendidos

| # | Requisito | Onde está implementado |
|---|---|---|
| 1 | Nome de contato + lista sempre visível na UI | `ClienteUI` — `JList listaContatos` no painel esquerdo, sempre renderizado |
| 2 | Alternar estado online/offline | `ClienteUI.aoAlternarStatus()` → `ClienteLogica.mudarStatus()` → `ServicoImpl.mudarStatus()` |
| 3 | Entrega instantânea quando online | `ServidorLogica.rotear()` — `online.contains(para)` → `cb.receberMensagem()` via callback RMI |
| 4 | Servidor remoto via RMI | `ServicoImpl extends UnicastRemoteObject`, publicado no `LocateRegistry` porta 1099 |
| 5 | Fila MOM por cliente | Broker ActiveMQ embutido; cada cliente tem uma `Queue` JMS `fila.<nome>` |
| 6 | Offline → fila do destinatário | `ServidorLogica.rotear()` → `enfileirar()` → `MessageProducer.send()` |
| 7 | Novo cliente solicita criação da fila | `ClienteLogica.conectar()` → RMI → `ServicoImpl.conectar()` → `garantirFila()` |
| 8 | Inclusão e exclusão de contatos | `ClienteUI` — botões `+ Adicionar` e `Remover`, persistidos em `contatos-<nome>.txt` |

---

## Estrutura dos Arquivos

```
ProjetoFinal/
├── src/
│   ├── Config.java             Constantes (porta RMI, URL broker, prefixo de fila)
│   ├── ClienteCallback.java    Interface RMI: servidor → cliente (push de eventos)
│   ├── ServicoMensagens.java   Interface RMI: cliente → servidor (contrato do serviço)
│   ├── ServidorLogica.java     LÓGICA do servidor: broker MOM + RMI + JMS
│   ├── ClienteLogica.java      LÓGICA do cliente: stub RMI + callback exportado
│   ├── ServidorUI.java         UI do servidor: janela de log
│   ├── ClienteUI.java          UI do cliente: lista de contatos + área de chat
│   └── Launcher.java           Janela inicial: abre servidor ou cliente
├── lib/                        JARs do ActiveMQ (broker, client, JMS API etc.)
├── MANIFEST.MF
├── Makefile
└── projeto-final.jar
```

---

## Como Executar

### Pré-requisito

Java JDK 11 ou superior instalado.

### Opção 1 — JAR

Extraia o ZIP ou faça git clone + link do repositório. Dentro da pasta extraída, execute:

```bash
java -jar projeto-final.jar
```

> A pasta `lib/` precisa estar no mesmo diretório que o `projeto-final.jar`.

### Opção 2 — Makefile

Ajuste `JAVA_HOME` no `Makefile` se necessário e execute:

```bash
make launcher    # compila e abre o Launcher
make servidor    # abre só a janela do servidor
make jar         # gera o projeto-final.jar
make clean       # remove arquivos compilados
```

---

## Passo a Passo da Demonstração

**1. Abrir o Launcher**

Execute o JAR. A janela inicial exibe dois botões.

**2. Iniciar o servidor**

Clique em **"Iniciar Servidor de Mensagens"**. A janela do servidor abre e o log exibe:

```
[HH:mm:ss] Iniciando broker ActiveMQ (MOM)...
[HH:mm:ss] Broker MOM no ar: tcp://localhost:61616
[HH:mm:ss] Conexão JMS aberta
[HH:mm:ss] Serviço RMI 'ServidorMensagens' publicado na porta 1099
[HH:mm:ss] Aguardando clientes...
```

**3. Abrir dois clientes**

Clique em **"+ Novo Cliente"** duas vezes. Informe os nomes, ex: `alice` e `bob`, host `localhost`.

**4. Adicionar contato (Req. 8)**

Na janela da Alice, clique em **"+ Adicionar"** e digite `bob`. O status de Bob aparece na lista.

**5. Trocar mensagens online (Req. 3)**

Alice envia uma mensagem para Bob. Bob recebe instantaneamente via callback RMI.

**6. Simular mensagem offline (Req. 6)**

1. Na janela do Bob, clique em **"Ir Offline"**.
2. Alice envia uma mensagem para Bob.
3. A conversa de Alice exibe: `(bob estava offline — msg guardada na fila)`.
4. O servidor loga: `[FILA] alice → bob`.

**7. Entregar mensagem da fila (Req. 2 + 4 + 5)**

Na janela do Bob, clique em **"Voltar Online"**. A mensagem é entregue marcada como `[offline→fila]`. O servidor loga: `'bob' voltou online (1 msgs)`.

**8. Fechar e reabrir o cliente (persistência da fila)**

Feche a janela do Bob completamente e abra um novo cliente com o nome `bob`. As mensagens que ficaram na fila são entregues automaticamente ao reconectar.

---

## Detalhes de Implementação

### Por que `setPersistent(false)` no broker?

O ActiveMQ com `setPersistent(true)` tenta criar a pasta `activemq-data/` no diretório de trabalho para armazenar as filas em disco (KahaDB). Em ambientes Windows sem permissão de escrita no diretório do JAR, isso gera o erro `Fatally failed to create SystemUsage`. Com `setPersistent(false)` o broker opera inteiramente em memória, eliminando o problema. As mensagens são preservadas enquanto o servidor estiver rodando.


### Thread safety

Chamadas RMI bloqueiam a thread chamadora. Por isso, toda chamada de rede em `ClienteUI` é feita em `new Thread(() -> { ... }).start()` e toda atualização de componente Swing usa `SwingUtilities.invokeLater()`. O estado compartilhado no servidor (`callbacks`, `online`, `filas`) usa `ConcurrentHashMap` e `ConcurrentHashMap.newKeySet()`.

---

## Dependências

Todos os JARs já estão incluídos na pasta `lib/`:

- `activemq-broker-5.17.6.jar`
- `activemq-client-5.17.6.jar`
- `activemq-openwire-legacy-5.17.6.jar`
- `geronimo-jms_1.1_spec-1.1.1.jar`
- `hawtbuf-1.11.jar`
- `jackson-annotations-2.15.3.jar` / `jackson-core-2.15.3.jar` / `jackson-databind-2.15.3.jar`
- `slf4j-api-1.7.36.jar` / `slf4j-simple-1.7.36.jar`
