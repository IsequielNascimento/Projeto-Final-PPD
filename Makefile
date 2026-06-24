# Projeto Final — Sistema de Mensagens Offline
# Uso: make launcher | make servidor | make jar | make clean

JAVA_HOME ?= C:/Program Files/Java/jdk-26.0.1
JAVAC     = "$(JAVA_HOME)/bin/javac"
JAVA      = "$(JAVA_HOME)/bin/java"
JAR_TOOL  = "$(JAVA_HOME)/bin/jar"

SRC = src/Config.java src/ClienteCallback.java src/ServicoMensagens.java \
      src/ServidorLogica.java src/ClienteLogica.java \
      src/ServidorUI.java src/ClienteUI.java src/Launcher.java

LIB   = lib/*
BUILD = build
SEP   = ;

.PHONY: all launcher servidor jar clean

all:
	-mkdir $(BUILD)
	$(JAVAC) -cp "$(LIB)" -d $(BUILD) $(SRC)

launcher: all
	$(JAVA) -cp "$(LIB)$(SEP)$(BUILD)" Launcher

servidor: all
	$(JAVA) -cp "$(LIB)$(SEP)$(BUILD)" ServidorUI

jar: all
	$(JAR_TOOL) cfm projeto-final.jar MANIFEST.MF -C $(BUILD) .
	@echo Gerado: projeto-final.jar
	@echo Executar: java -jar projeto-final.jar

clean:
	rm -rf $(BUILD) projeto-final.jar
