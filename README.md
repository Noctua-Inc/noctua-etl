# noctua-etl

## Exemplo de estrutura no S3

```text
noctua-s3/
└── são_paulo_tech_school/
    ├── 01-bronze/
    │   └── laptop-Itz_4thu/
    │       ├── laptop-Itz_4thu_2026-11-09_20-38.csv
    │       └── laptop-Itz_4thu_processos_2026-11-09_20-38.csv
    ├── 02-silver/
    │   └── laptop-Itz_4thu/
    │       ├── laptop-Itz_4thu_2026-11-09_20-38.csv
    │       └── laptop-Itz_4thu_historico.csv
    └── 03-gold/
        └── laptop-Itz_4thu/
            ├── laptop-Itz_4thu_2026-11-09_20-38_todos_registros.json
            ├── laptop-Itz_4thu_2026-11-09_20-38_cpu_registros.json
            ├── laptop-Itz_4thu_2026-11-09_20-38_ram_registros.json
            ├── laptop-Itz_4thu_2026-11-09_20-38_rede_registros.json
            └── laptop-Itz_4thu_2026-11-09_20-38_disco_registros.json
```

- **Bronze** guarda os CSVs brutos de métricas e de processos enviados pelo script de captura.
- **Silver** guarda o último lote tratado e o histórico de sete dias por mainframe. O histórico tem uma linha por minuto com as médias de CPU, RAM e ocupação do disco.
- **Gold** terá uma pasta por mainframe para os JSONs produzidos pelo Silver2Gold.

## Módulos da ETL

- `bronze2silver/` trata os CSVs de métricas e atualiza o histórico. Para os CSVs de processos, grava
  o lote padronizado em `empresa/02-silver/{hostname}/..._silver.csv` e as linhas rejeitadas em
  `..._quarentena.csv`.
- `silver2gold/` gera as previsões a partir do histórico e publica
  `empresa/03-gold/{hostname}/processos/latest.json` a partir dos lotes de processos da Silver.

Em cada módulo, `mvn package` gera o JAR com dependências em `target/`. O handler
`com.noctua.conection.lambda.EntradaLambda::handleRequest` encaminha os eventos do S3 conforme a chave do arquivo.
Na execução manual com `java -jar`, acrescente `processos` após o nome do JAR para processar o último lote de processos.
