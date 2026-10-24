import csv
import json
import os
import time
import boto3
from datetime import datetime
from botocore.exceptions import ClientError, NoCredentialsError

try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

for _var in ("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN"):
    if not os.environ.get(_var):
        os.environ.pop(_var, None)

BUCKET = os.getenv("BUCKET", "bucket-noctua")
INTERVALO = int(os.getenv("INTERVALO", "60"))
REGIAO = os.getenv("AWS_REGION", "us-east-1")
PREFIXO_BRONZE = os.getenv("PREFIXO_BRONZE", "bronze/")

session = boto3.Session(region_name=REGIAO)
s3 = session.client("s3")

HOSTNAMELOCAL = os.getenv("HOSTNAME_LOCAL") or input("Digite o hostname do computador: ")

PASTA_CSV = os.path.dirname(os.path.abspath(__file__))
CAMINHO_CSV = os.path.join(PASTA_CSV, HOSTNAMELOCAL + ".csv")
CAMINHO_PROCESSOS = os.path.join(PASTA_CSV, HOSTNAMELOCAL + "_processos.csv")


def ler_csv(caminho, avisar=True):
    if not os.path.exists(caminho):
        if avisar:
            print(f"Arquivo não encontrado: {caminho}")
        return []
    with open(caminho, "r", newline="", encoding="utf-8") as arquivo:
        return list(csv.DictReader(arquivo))


ultimo_ts = "" 

while True:
    if not os.path.exists(CAMINHO_CSV):
        print("Aguardando o arquivo de métricas...")
        time.sleep(INTERVALO)
        continue

    metricas = [l for l in ler_csv(CAMINHO_CSV) if l["Timestamp"] > ultimo_ts]
    if not metricas:
        print("Sem dados novos.")
        time.sleep(INTERVALO)
        continue

    agora = datetime.now()
    dados = {
        "hostname": HOSTNAMELOCAL,
        "timestamp_envio": agora.strftime("%Y-%m-%d %H:%M:%S"),
        "metricas": metricas,
        "processos": ler_csv(CAMINHO_PROCESSOS, avisar=False),
    }

    nome_arquivo = f"dados_{HOSTNAMELOCAL}_{agora.strftime('%Y%m%d_%H%M%S')}.json"
    caminho_json = os.path.join(PASTA_CSV, nome_arquivo)

    with open(caminho_json, "w", encoding="utf-8") as arquivo:
        json.dump(dados, arquivo, indent=4, ensure_ascii=False)

    try:
        s3.upload_file(
            Filename=caminho_json,
            Bucket=BUCKET,
            Key=f"{PREFIXO_BRONZE}{nome_arquivo}",
        )
        ultimo_ts = metricas[-1]["Timestamp"] 
        print(f"Arquivo enviado: {PREFIXO_BRONZE}{nome_arquivo} ({len(metricas)} linhas)")
    except NoCredentialsError:
        print("Credenciais AWS não encontradas.")
    except ClientError as e:
        print(f"Erro ao enviar para o S3: {e}")
    finally:
        if os.path.exists(caminho_json):
            os.remove(caminho_json)

    time.sleep(INTERVALO)
