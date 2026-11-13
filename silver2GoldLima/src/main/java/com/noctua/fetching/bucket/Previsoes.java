package com.noctua.fetching.bucket;

import com.google.gson.*;
import com.noctua.conection.bucket.S3Provider;
import com.noctua.conection.jdbc.DBConnectionProvider;
import com.noctua.model.Limite;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Previsoes {
    private final Clock relogio;
    private final S3Client clienteTeste;
    private final JdbcTemplate bancoTeste;
    private final String bucketTeste;

    public Previsoes() {
        this(Clock.system(CalculosPrevisoes.FUSO), null, null, null);
    }

    Previsoes(Clock relogio, S3Client s3, JdbcTemplate jdbc, String bucket) {
        this.relogio = relogio;
        this.clienteTeste = s3;
        this.bancoTeste = jdbc;
        this.bucketTeste = bucket;
    }

    static class Contexto {
        int idEmpresa;
        int idMainframe;
        String empresa;
        String hostname;
        List<Limite> limites;
    }

    public void executar(String chave) throws Exception {
        if (!chave.endsWith("_historico.csv") || !chave.contains("/02-silver/")) return;
        if (clienteTeste != null) {
            executar(chave, clienteTeste, bancoTeste, bucketTeste);
            return;
        }
        DBConnectionProvider conexao = new DBConnectionProvider();
        try (S3Client s3 = new S3Provider().getS3Client()) {
            executar(chave, s3, conexao.getJdbcTemplate(), Dotenv.configure().ignoreIfMissing().load().get("BUCKET_NAME"));
        } finally {
            conexao.fechar();
        }
    }

    private void executar(String chave, S3Client s3, JdbcTemplate jdbc, String bucket) throws Exception {
        Contexto contexto = consultarMainframe(jdbc, chave);
        byte[] csv;
        try (ResponseInputStream<?> entrada = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(chave).build())) {
            csv = entrada.readAllBytes();
        }
        CalculosPrevisoes.Serie serie = lerHistorico(csv, contexto.hostname);
        LocalDateTime gerado = LocalDateTime.ofInstant(relogio.instant(), CalculosPrevisoes.FUSO);
        String base = contexto.empresa + "/03-gold/" + contexto.hostname + "/";
        HistoricoPrevisoes arquivos = new HistoricoPrevisoes(s3, bucket);
        CalculosPrevisoes calculos = new CalculosPrevisoes();

        JsonObject documento = new JsonObject();
        documento.addProperty("id_empresa", contexto.idEmpresa);
        documento.addProperty("id_mainframe", contexto.idMainframe);
        documento.addProperty("hostname", contexto.hostname);
        documento.addProperty("atualizado_em", CalculosPrevisoes.horario(serie.atual));
        documento.addProperty("gerado_em", CalculosPrevisoes.horario(gerado));
        documento.addProperty("capturas_no_minuto", serie.minutos[serie.minutos.length - 1].quantidade);
        JsonObject componentes = new JsonObject();
        JsonObject cpu = new JsonObject();
        JsonObject ram = new JsonObject();
        JsonObject disco = new JsonObject();
        JsonObject[] metricas = new JsonObject[3];
        for (int m = 0; m < 3; m++) {
            Double[] faixa = buscarFaixa(contexto.limites, new String[]{"CPU", "RAM", "DISCO"}[m]);
            metricas[m] = calculos.calcular(serie, m, faixa[0], faixa[1], gerado);
        }

        JsonObject versao = criarVersao(csv, serie, gerado, metricas);
        JsonArray anteriores = arquivos.atualizar(base + "historico-previsoes/previsoes.json", versao, serie.atual);
        for (int m = 0; m < 3; m++) arquivos.preencherErros(metricas[m], m, anteriores, serie);
        cpu.add("uso", metricas[0]);
        ram.add("uso", metricas[1]);
        disco.add("ocupacao", metricas[2]);
        disco.add("leitura", metricaSemCapturas(serie.atual));
        disco.add("escrita", metricaSemCapturas(serie.atual));
        componentes.add("cpu", cpu);
        componentes.add("ram", ram);
        componentes.add("disco", disco);
        documento.add("componentes", componentes);
        publicarAtual(arquivos, base + "previsoes.json", documento);
        atualizarResumo(arquivos, jdbc, contexto);
    }

    private Contexto consultarMainframe(JdbcTemplate jdbc, String chave) {
        String[] partes = chave.split("/");
        Contexto contexto = new Contexto();
        contexto.empresa = partes[0];
        String nome = partes[partes.length - 1];
        contexto.hostname = nome.substring(0, nome.length() - "_historico.csv".length());
        contexto.idEmpresa = jdbc.queryForObject("""
                SELECT id_empresa FROM empresa
                WHERE REPLACE(LOWER(razao_social), ' ', '_') = ?
                """, Integer.class, contexto.empresa);
        contexto.idMainframe = jdbc.queryForObject("""
                SELECT id_mainframe FROM mainframe
                WHERE hostname = ? AND fk_empresa = ?
                """, Integer.class, contexto.hostname, contexto.idEmpresa);
        contexto.limites = jdbc.query("""
                SELECT c.tipo, p.pico_min, p.pico_max
                FROM parametro p JOIN componente c ON c.id_componente = p.id_componente
                WHERE p.id_mainframe = ? ORDER BY c.tipo
                """, new BeanPropertyRowMapper<>(Limite.class), contexto.idMainframe);
        return contexto;
    }

    CalculosPrevisoes.Serie lerHistorico(byte[] csv, String hostname) throws IOException {
        ArrayList<CalculosPrevisoes.Leitura> leituras = new ArrayList<>();
        String[] linhas = new String(csv, StandardCharsets.UTF_8).lines().toArray(String[]::new);
        String[] cabecalho = linhas[0].replace("﻿", "").split(",", -1);
        int colunaData = coluna(cabecalho, "Timestamp");
        int colunaHost = coluna(cabecalho, "HostName");
        int cpu = coluna(cabecalho, "Porcentagem_CPU");
        int ram = coluna(cabecalho, "Porcentagem_RAM");
        int disco = coluna(cabecalho, "Porcentagem_Disco");
        int quantidade = -1;
        for (int i = 0; i < cabecalho.length; i++) if (cabecalho[i].equals("Quantidade_Capturas")) quantidade = i;
        DateTimeFormatter formato = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (int i = 1; i < linhas.length; i++) {
            if (linhas[i].isBlank()) continue;
            String[] valores = linhas[i].split(",", -1);
            if (!valores[colunaHost].equals(hostname)) throw new IOException("Hostname diferente dentro do histórico.");
            int contagem = quantidade < 0 ? 1 : Integer.parseInt(valores[quantidade]);
            double valorCpu = Double.parseDouble(valores[cpu]);
            double valorRam = Double.parseDouble(valores[ram]);
            double valorDisco = Double.parseDouble(valores[disco]);
            if (contagem <= 0 || !Double.isFinite(valorCpu) || !Double.isFinite(valorRam) || !Double.isFinite(valorDisco)) {
                throw new IOException("Captura com quantidade ou percentuais inválidos.");
            }
            leituras.add(new CalculosPrevisoes.Leitura(
                    LocalDateTime.parse(valores[colunaData], formato).withSecond(0).withNano(0),
                    contagem, valorCpu, valorRam, valorDisco));
        }
        return new CalculosPrevisoes.Serie(leituras);
    }

    private int coluna(String[] cabecalho, String nome) throws IOException {
        for (int i = 0; i < cabecalho.length; i++) if (cabecalho[i].equals(nome)) return i;
        throw new IOException("Coluna não encontrada: " + nome);
    }

    private Double[] buscarFaixa(List<Limite> limites, String tipo) {
        Double minimo = null, maximo = null;
        for (Limite limite : limites) {
            if (tipo.equalsIgnoreCase(limite.getTipo())) {
                if (limite.getPicoMin() != null) minimo = minimo == null ? limite.getPicoMin().doubleValue() : Math.max(minimo, limite.getPicoMin());
                if (limite.getPicoMax() != null) maximo = maximo == null ? limite.getPicoMax().doubleValue() : Math.min(maximo, limite.getPicoMax());
            }
        }
        return new Double[]{minimo, maximo};
    }

    private JsonObject criarVersao(byte[] csv, CalculosPrevisoes.Serie serie, LocalDateTime gerado, JsonObject[] metricas) throws Exception {
        JsonObject versao = new JsonObject();
        // O mesmo conteúdo de Silver não cria outra versão quando o evento é repetido.
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(csv);
        versao.addProperty("id", HexFormat.of().formatHex(hash));
        versao.addProperty("origem", CalculosPrevisoes.horario(serie.atual));
        versao.addProperty("gerada_em", CalculosPrevisoes.horario(gerado));
        JsonArray itens = new JsonArray();
        for (int m = 0; m < 3; m++) {
            JsonObject item = new JsonObject();
            item.addProperty("metrica", CalculosPrevisoes.NOMES[m]);
            item.add("pontos", metricas[m].getAsJsonObject("graficos").getAsJsonArray("previsao").deepCopy());
            itens.add(item);
        }
        versao.add("metricas", itens);
        return versao;
    }

    static JsonObject metrica(JsonObject documento, int m) {
        JsonObject componentes = documento.getAsJsonObject("componentes");
        if (m == 0) return componentes.getAsJsonObject("cpu").getAsJsonObject("uso");
        if (m == 1) return componentes.getAsJsonObject("ram").getAsJsonObject("uso");
        return componentes.getAsJsonObject("disco").getAsJsonObject("ocupacao");
    }

    private JsonObject metricaSemCapturas(LocalDateTime atual) {
        JsonObject resultado = new JsonObject();
        resultado.addProperty("unidade", "MiB/s");
        resultado.add("limite_minimo", JsonNull.INSTANCE);
        resultado.add("limite_maximo", JsonNull.INSTANCE);
        JsonObject indicadores = new JsonObject();
        for (String nome : new String[]{"maior_previsto", "menor_previsto", "primeira_saida_em_minutos", "primeira_saida_tipo", "erro_medio"}) {
            indicadores.add(nome, JsonNull.INSTANCE);
        }
        indicadores.addProperty("unidade_erro", "MiB/s");
        resultado.add("indicadores", indicadores);
        JsonObject graficos = new JsonObject();
        graficos.add("atual", CalculosPrevisoes.ponto(atual, null));
        for (String nome : new String[]{"historico", "previsao", "mapa"}) graficos.add(nome, new JsonArray());
        resultado.add("graficos", graficos);
        resultado.addProperty("motivo", "O Silver ainda não fornece leitura e escrita globais do disco.");
        return resultado;
    }

    private void publicarAtual(HistoricoPrevisoes arquivos, String chave, JsonObject novo) throws IOException {
        for (int tentativa = 0; tentativa < 4; tentativa++) {
            HistoricoPrevisoes.Arquivo atual = arquivos.ler(chave);
            if (atual.dados.has("atualizado_em")) {
                LocalDateTime dataAtual = CalculosPrevisoes.data(atual.dados.get("atualizado_em").getAsString());
                LocalDateTime dataNova = CalculosPrevisoes.data(novo.get("atualizado_em").getAsString());
                if (dataAtual.isAfter(dataNova)) return;
                if (dataAtual.equals(dataNova) && atual.dados.has("capturas_no_minuto")
                        && atual.dados.get("capturas_no_minuto").getAsInt() > novo.get("capturas_no_minuto").getAsInt()) return;
                if (dataAtual.equals(dataNova) && CalculosPrevisoes.data(atual.dados.get("gerado_em").getAsString())
                        .isAfter(CalculosPrevisoes.data(novo.get("gerado_em").getAsString()))) return;
            }
            if (arquivos.gravar(chave, novo, atual.etag)) return;
        }
        throw new IOException("O documento da mainframe mudou durante o envio. Reexecute este evento.");
    }

    private void atualizarResumo(HistoricoPrevisoes arquivos, JdbcTemplate jdbc, Contexto contexto) throws IOException {
        List<String> hostnames = jdbc.queryForList("""
                SELECT hostname FROM mainframe WHERE fk_empresa = ? AND status <> 'inativo' ORDER BY id_mainframe
                """, String.class, contexto.idEmpresa);
        String chave = contexto.empresa + "/03-gold/previsoes/resumo.json";
        for (int tentativa = 0; tentativa < 4; tentativa++) {
            HistoricoPrevisoes.Arquivo anterior = arquivos.ler(chave);
            JsonArray documentos = new JsonArray();
            LocalDateTime referencia = null;
            for (String hostname : hostnames) {
                JsonObject documento = arquivos.ler(contexto.empresa + "/03-gold/" + hostname + "/previsoes.json").dados;
                if (!documento.has("componentes")) continue;
                documentos.add(documento);
                LocalDateTime data = CalculosPrevisoes.data(documento.get("atualizado_em").getAsString());
                if (referencia == null || data.isAfter(referencia)) referencia = data;
            }
            if (referencia == null) return;
            JsonObject resumo = new JsonObject();
            resumo.addProperty("id_empresa", contexto.idEmpresa);
            resumo.addProperty("atualizado_em", CalculosPrevisoes.horario(referencia));
            JsonObject componentes = new JsonObject();
            for (int m = 0; m < 3; m++) {
                JsonObject grupo = new JsonObject();
                JsonObject resultado = new JsonObject();
                resultado.addProperty("unidade", "%");
                JsonArray linhas = new JsonArray();
                for (JsonElement elemento : documentos) {
                    JsonObject documento = elemento.getAsJsonObject();
                    JsonObject metrica = metrica(documento, m);
                    Double menor = null, maior = null;
                    for (JsonElement p : metrica.getAsJsonObject("graficos").getAsJsonArray("previsao")) {
                        JsonObject ponto = p.getAsJsonObject();
                        LocalDateTime horario = CalculosPrevisoes.data(ponto.get("horario").getAsString());
                        if (horario.isAfter(referencia) && !horario.isAfter(CalculosPrevisoes.fimGrafico(referencia))) {
                            double valor = ponto.get("valor").getAsDouble();
                            menor = menor == null ? valor : Math.min(menor, valor);
                            maior = maior == null ? valor : Math.max(maior, valor);
                        }
                    }
                    JsonObject linha = new JsonObject();
                    linha.add("id_mainframe", documento.get("id_mainframe"));
                    linha.add("hostname", documento.get("hostname"));
                    CalculosPrevisoes.numero(linha, "menor_previsto", menor);
                    CalculosPrevisoes.numero(linha, "maior_previsto", maior);
                    linha.add("limite_minimo", metrica.get("limite_minimo"));
                    linha.add("limite_maximo", metrica.get("limite_maximo"));
                    String situacao = null;
                    if (menor != null && !metrica.get("limite_minimo").isJsonNull() && !metrica.get("limite_maximo").isJsonNull()) {
                        boolean abaixo = menor < metrica.get("limite_minimo").getAsDouble();
                        boolean acima = maior > metrica.get("limite_maximo").getAsDouble();
                        situacao = abaixo && acima ? "Abaixo e acima" : abaixo ? "Abaixo do mínimo" : acima ? "Acima do máximo" : "Dentro da faixa";
                    }
                    linha.addProperty("situacao", situacao);
                    linhas.add(linha);
                }
                resultado.add("mainframes", linhas);
                grupo.add(m < 2 ? "uso" : "ocupacao", resultado);
                if (m == 2) {
                    for (String nome : new String[]{"leitura", "escrita"}) {
                        JsonObject vazio = new JsonObject();
                        vazio.addProperty("unidade", "MiB/s");
                        vazio.add("mainframes", new JsonArray());
                        grupo.add(nome, vazio);
                    }
                }
                componentes.add(new String[]{"cpu", "ram", "disco"}[m], grupo);
            }
            resumo.add("componentes", componentes);
            if (arquivos.gravar(chave, resumo, anterior.etag)) return;
        }
        throw new IOException("O resumo mudou durante o envio. Reexecute este evento.");
    }
}
