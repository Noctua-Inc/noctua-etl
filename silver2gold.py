import csv
import io
import boto3
from botocore.exceptions import ClientError, NoCredentialsError

BUCKET = "bucket-noctua"
REGIAO = "us-east-1"

PREFIXO_METRICAS = "silver/metricas/"
PREFIXO_PROCESSOS = "silver/processos/"
PREFIXO_GOLD = "gold/"

LIMITE_ALTO = 80  
TOP_PROCESSOS = 20

session = boto3.Session(region_name=REGIAO)
s3 = session.client("s3")



def numero(valor):
    try:
        if valor is None or str(valor).strip() == "":
            return None
        return float(valor)
    except (ValueError, TypeError):
        return None


def media(valores):
    validos = [v for v in valores if v is not None]
    return round(sum(validos) / len(validos), 2) if validos else None


def maximo(valores):
    validos = [v for v in valores if v is not None]
    return max(validos) if validos else None


def status(valor):
    if valor is None:
        return "Sem dados"
    return "Alto" if valor > LIMITE_ALTO else "Normal"


def ler_pasta_silver(prefixo):
    registros = []
    paginator = s3.get_paginator("list_objects_v2")
    for pagina in paginator.paginate(Bucket=BUCKET, Prefix=prefixo):
        for obj in pagina.get("Contents", []):
            key = obj["Key"]
            if not key.endswith(".csv"):
                continue
            corpo = s3.get_object(Bucket=BUCKET, Key=key)["Body"].read()
            texto = corpo.decode("utf-8")
            registros.extend(csv.DictReader(io.StringIO(texto)))
    return registros


def enviar_csv(nome, dados):
    if not dados:
        print(f"Nenhum dado para gerar: {nome}")
        return

    saida = io.StringIO()
    escritor = csv.DictWriter(saida, fieldnames=list(dados[0].keys()))
    escritor.writeheader()
    escritor.writerows(dados)

    s3.put_object(
        Bucket=BUCKET,
        Key=f"{PREFIXO_GOLD}{nome}",
        Body=saida.getvalue().encode("utf-8"),
        ContentType="text/csv",
    )
    print(f"Enviado para {PREFIXO_GOLD}{nome} ({len(dados)} linhas)")


def vazio_se_none(valor):
    return "" if valor is None else valor



def gerar_desempenho(metricas):
    vistos = set()
    desempenho = []

    for item in metricas:
        chave = (item.get("hostname"), item.get("timestamp"))
        if chave in vistos:
            continue
        vistos.add(chave)

        cpu = numero(item.get("cpu_pct"))
        ram = numero(item.get("ram_pct"))
        disco = numero(item.get("disco_pct"))
        swap = numero(item.get("swap_pct"))

        desempenho.append({
            "HostName": item.get("hostname", ""),
            "Timestamp": item.get("timestamp", ""),
            "CPU_percentual": vazio_se_none(cpu),
            "RAM_percentual": vazio_se_none(ram),
            "Disco_percentual": vazio_se_none(disco),
            "SWAP_percentual": vazio_se_none(swap),
            "Freq_CPU_MHz": vazio_se_none(numero(item.get("cpu_freq_mhz"))),
            "RAM_disponivel_GB": vazio_se_none(numero(item.get("ram_disponivel_gb"))),
            "Disco_disponivel_GB": vazio_se_none(numero(item.get("disco_livre_gb"))),
            "Load": vazio_se_none(numero(item.get("load_pct"))),
            "Status_CPU": status(cpu),
            "Status_RAM": status(ram),
            "Status_Disco": status(disco),
        })

    desempenho.sort(key=lambda x: (x["HostName"], x["Timestamp"]))
    return desempenho



def gerar_processos(processos):
    lista = []
    for item in processos:
        lista.append({
            "HostName": item.get("hostname", ""),
            "Timestamp": item.get("timestamp_envio", ""),
            "PID": item.get("PID", ""),
            "Nome_Processo": item.get("Nome_Processo", ""),
            "CPU_percentual": numero(item.get("Porcentagem_CPU_Processo")) or 0.0,
            "Memoria_MB": numero(item.get("Memoria_MB")) or 0.0,
            "Bytes_Lidos": numero(item.get("Bytes_Lidos_Acumulados")) or 0.0,
            "Bytes_Escritos": numero(item.get("Bytes_Escritos_Acumulados")) or 0.0,
        })

    lista.sort(key=lambda x: x["Memoria_MB"], reverse=True)
    return lista[:TOP_PROCESSOS]



def gerar_resumo(desempenho):
    por_host = {}
    for item in desempenho:
        por_host.setdefault(item["HostName"], []).append(item)

    resumo = []
    for hostname, regs in por_host.items():
        cpu = [numero(r["CPU_percentual"]) for r in regs]
        ram = [numero(r["RAM_percentual"]) for r in regs]
        disco = [numero(r["Disco_percentual"]) for r in regs]
        swap = [numero(r["SWAP_percentual"]) for r in regs]

        resumo.append({
            "HostName": hostname,
            "Total_capturas": len(regs),
            "Primeira_captura": regs[0]["Timestamp"],
            "Ultima_captura": regs[-1]["Timestamp"],
            "Media_CPU": vazio_se_none(media(cpu)),
            "Pico_CPU": vazio_se_none(maximo(cpu)),
            "Media_RAM": vazio_se_none(media(ram)),
            "Pico_RAM": vazio_se_none(maximo(ram)),
            "Media_Disco": vazio_se_none(media(disco)),
            "Pico_Disco": vazio_se_none(maximo(disco)),
            "Media_SWAP": vazio_se_none(media(swap)),
            "Pico_SWAP": vazio_se_none(maximo(swap)),
        })
    return resumo


def main():
    try:
        metricas = ler_pasta_silver(PREFIXO_METRICAS)
        processos = ler_pasta_silver(PREFIXO_PROCESSOS)
    except NoCredentialsError:
        print("Credenciais AWS não encontradas.")
        return
    except ClientError as e:
        print(f"Erro ao ler a silver: {e}")
        return

    print(f"Silver lida: {len(metricas)} métricas, {len(processos)} processos")

    if not metricas:
        print("Silver sem métricas. Rode o bronze e o bronze2silver antes.")
        return

    desempenho = gerar_desempenho(metricas)
    enviar_csv("desempenho_computadores.csv", desempenho)
    enviar_csv("resumo_computadores.csv", gerar_resumo(desempenho))
    enviar_csv("processos_mais_pesados.csv", gerar_processos(processos))

    print("Relatórios Gold gerados com sucesso!")


if __name__ == "__main__":
    main()