import csv
import io
import json
import time
import boto3
from datetime import datetime
from botocore.exceptions import ClientError, NoCredentialsError

BUCKET = "bucket-noctua"
REGIAO = "us-east-1"
INTERVALO = 60

PREFIXO_BRONZE = "bronze/"
PREFIXO_PROCESSADOS = "bronze/processados/"
PREFIXO_SILVER = "silver/"

session = boto3.Session(region_name=REGIAO)
s3 = session.client("s3")

COLUNAS = {
    "HostName": "hostname",
    "Porcentagem_CPU": "cpu_pct",
    "Freq_CPU": "cpu_freq_mhz",
    "Porcentagem_RAM": "ram_pct",
    "Total_RAM": "ram_total_gb",
    "Disponivel_RAM": "ram_disponivel_gb",
    "Porcentagem_Disco": "disco_pct",
    "Disponivel_Disco": "disco_livre_gb",
    "Swap": "swap_pct",
    "Load": "load_pct",
}
EM_BYTES = ("ram_total_gb", "ram_disponivel_gb", "disco_livre_gb")
PERCENTUAIS = ("cpu_pct", "ram_pct", "disco_pct", "swap_pct")
FORMATO_TS = "%Y-%m-%d %H:%M:%S"


def converter(valor):
    if not isinstance(valor, str):
        return valor
    valor = valor.strip()
    if valor == "":
        return None
    try:
        return int(valor)
    except ValueError:
        pass
    try:
        return float(valor)
    except ValueError:
        return valor


def limpar_metricas(registros, hostname_padrao):
    vistos = set()
    limpas = []

    for r in registros:
        linha = {novo: converter(r.get(orig)) for orig, novo in COLUNAS.items()}

        if not linha.get("hostname"):
            linha["hostname"] = hostname_padrao

        try:
            ts = datetime.strptime(str(r.get("Timestamp", "")).strip(), FORMATO_TS)
        except ValueError:
            continue
        linha["timestamp"] = ts.strftime(FORMATO_TS)

        invalido = False
        for c in PERCENTUAIS:
            v = linha[c]
            if not isinstance(v, (int, float)) or not 0 <= v <= 100:
                invalido = True
                break
        if invalido:
            continue

        for c in ("cpu_freq_mhz", "load_pct") + EM_BYTES:
            v = linha[c]
            if not isinstance(v, (int, float)) or v < 0:
                linha[c] = None

        for c in EM_BYTES:
            if linha[c] is not None:
                linha[c] = round(linha[c] / 1024 ** 3, 2)

        chave = (linha["hostname"], linha["timestamp"])
        if chave in vistos:
            continue
        vistos.add(chave)

        limpas.append(linha)

    return limpas


def limpar_processos(registros, hostname, ts_envio):
    linhas = []
    for reg in registros:
        linha = {"hostname": hostname, "timestamp_envio": ts_envio}
        linha.update({k: converter(v) for k, v in reg.items()})
        linhas.append(linha)
    return linhas


def linhas_para_csv(linhas):
    campos = []
    for linha in linhas:
        for c in linha:
            if c not in campos:
                campos.append(c)
    buffer = io.StringIO()
    escritor = csv.DictWriter(buffer, fieldnames=campos)
    escritor.writeheader()
    escritor.writerows(linhas)
    return buffer.getvalue()


def listar_jsons_bronze():
    paginator = s3.get_paginator("list_objects_v2")
    chaves = []
    for pagina in paginator.paginate(Bucket=BUCKET, Prefix=PREFIXO_BRONZE):
        for obj in pagina.get("Contents", []):
            key = obj["Key"]
            if key.endswith(".json") and not key.startswith(PREFIXO_PROCESSADOS):
                chaves.append(key)
    return chaves


def processar(key):
    obj = s3.get_object(Bucket=BUCKET, Key=key)
    dados = json.loads(obj["Body"].read().decode("utf-8"))

    hostname = dados["hostname"]
    ts_envio = dados["timestamp_envio"]
    nome_csv = key.split("/")[-1].replace(".json", ".csv")

    brutas = dados.get("metricas", [])
    metricas = limpar_metricas(brutas, hostname)
    descartadas = len(brutas) - len(metricas)

    if metricas:
        s3.put_object(
            Bucket=BUCKET,
            Key=f"{PREFIXO_SILVER}metricas/{nome_csv}",
            Body=linhas_para_csv(metricas).encode("utf-8"),
        )

    processos = limpar_processos(dados.get("processos", []), hostname, ts_envio)
    if processos:
        s3.put_object(
            Bucket=BUCKET,
            Key=f"{PREFIXO_SILVER}processos/{nome_csv}",
            Body=linhas_para_csv(processos).encode("utf-8"),
        )

    destino = PREFIXO_PROCESSADOS + key.split("/")[-1]
    s3.copy_object(
        Bucket=BUCKET, Key=destino, CopySource={"Bucket": BUCKET, "Key": key}
    )
    s3.delete_object(Bucket=BUCKET, Key=key)

    print(
        f"Processado: {key} -> {len(metricas)} métricas, "
        f"{len(processos)} processos, {descartadas} descartadas"
    )


def main():
    while True:
        try:
            chaves = listar_jsons_bronze()
            if not chaves:
                print("Nenhum JSON novo em bronze/")
            for key in chaves:
                try:
                    processar(key)
                except (ClientError, KeyError, json.JSONDecodeError) as e:
                    print(f"Erro ao processar {key}: {e}")
        except NoCredentialsError:
            print("Credenciais AWS não encontradas.")
        except ClientError as e:
            print(f"Erro ao acessar o S3: {e}")

        time.sleep(INTERVALO)


if __name__ == "__main__":
    main()