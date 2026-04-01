@echo off
echo Buscando todos os arquivos .java...
:: Cria um arquivo temporario com os caminhos de todos os arquivos .java
dir /s /b *.java > sources.txt

echo Compilando o projeto...
:: O simbolo @ faz o javac ler o arquivo txt e compilar tudo o que esta listado nele
javac @sources.txt

:: Verifica se houve erro na compilacao
if %errorlevel% neq 0 (
    echo Erro ao compilar. Verifique seu codigo.
    del sources.txt
    pause
    exit /b
)

:: Apaga o arquivo temporario pois nao precisamos mais dele
del sources.txt
echo Compilacao concluida com sucesso!

set /p num="Quantas instancias deseja rodar? "
for /l %%x in (1, 1, %num%) do (
   echo Iniciando instancia %%x...
   start "Instancia %%x" java app.AppMain
)

echo Todas as instancias foram abertas!
pause