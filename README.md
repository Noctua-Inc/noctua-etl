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
