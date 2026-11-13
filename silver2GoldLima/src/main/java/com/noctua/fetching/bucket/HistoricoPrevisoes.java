package com.noctua.fetching.bucket;

import com.google.gson.*;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;

class HistoricoPrevisoes {
    private final S3Client s3;
    private final String bucket;
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    static class Arquivo {
        JsonObject dados;
        String etag;

        Arquivo(JsonObject dados, String etag) {
            this.dados = dados;
            this.etag = etag;
        }
    }

    HistoricoPrevisoes(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    Arquivo ler(String chave) throws IOException {
        try (ResponseInputStream<GetObjectResponse> entrada = s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(chave).build())) {
            String texto = new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
            return new Arquivo(JsonParser.parseString(texto).getAsJsonObject(), entrada.response().eTag());
        } catch (NoSuchKeyException erro) {
            return new Arquivo(new JsonObject(), null);
        }
    }

    boolean gravar(String chave, JsonObject dados, String etag) {
        PutObjectRequest.Builder pedido = PutObjectRequest.builder().bucket(bucket).key(chave)
                .contentType("application/json; charset=utf-8");
        if (etag == null) pedido.overrideConfiguration(c -> c.putHeader("If-None-Match", "*"));
        else pedido.overrideConfiguration(c -> c.putHeader("If-Match", etag));
        try {
            s3.putObject(pedido.build(), RequestBody.fromString(gson.toJson(dados), StandardCharsets.UTF_8));
            return true;
        } catch (S3Exception erro) {
            if (erro.statusCode() == 409 || erro.statusCode() == 412) return false;
            throw erro;
        }
    }

    JsonArray atualizar(String chave, JsonObject versao, LocalDateTime atual) throws IOException {
        for (int tentativa = 0; tentativa < 4; tentativa++) {
            Arquivo arquivo = ler(chave);
            LocalDateTime referencia = atual;
            if (arquivo.dados.has("versoes")) {
                for (JsonElement elemento : arquivo.dados.getAsJsonArray("versoes")) {
                    LocalDateTime origem = CalculosPrevisoes.data(elemento.getAsJsonObject().get("origem").getAsString());
                    if (origem.isAfter(referencia)) referencia = origem;
                }
            }
            JsonArray versoes = limpar(arquivo.dados, referencia);
            boolean existe = false;
            for (JsonElement antiga : versoes) {
                if (antiga.getAsJsonObject().get("id").getAsString().equals(versao.get("id").getAsString())) existe = true;
            }
            if (!existe && !atual.isBefore(CalculosPrevisoes.inicioGrafico(referencia))) versoes.add(versao);
            JsonObject documento = new JsonObject();
            documento.addProperty("inicio_janela", CalculosPrevisoes.horario(CalculosPrevisoes.inicioGrafico(referencia)));
            documento.add("versoes", versoes);
            if (gravar(chave, documento, arquivo.etag)) return versoes;
        }
        throw new IOException("O histórico de previsões mudou durante o envio. Reexecute este evento.");
    }

    JsonArray limpar(JsonObject arquivo, LocalDateTime atual) {
        LocalDateTime inicio = CalculosPrevisoes.inicioGrafico(atual);
        LocalDateTime fim = CalculosPrevisoes.fimGrafico(atual);
        JsonArray mantidas = new JsonArray();
        if (!arquivo.has("versoes")) return mantidas;
        for (JsonElement elemento : arquivo.getAsJsonArray("versoes")) {
            JsonObject versao = elemento.getAsJsonObject();
            LocalDateTime origem = CalculosPrevisoes.data(versao.get("origem").getAsString());
            // Uma execução atrasada não pode apagar versões de execuções mais novas.
            if (!origem.isBefore(inicio)) {
                JsonObject copia = versao.deepCopy();
                for (JsonElement metrica : copia.getAsJsonArray("metricas")) {
                    JsonObject objeto = metrica.getAsJsonObject();
                    JsonArray pontos = new JsonArray();
                    for (JsonElement ponto : objeto.getAsJsonArray("pontos")) {
                        LocalDateTime alvo = CalculosPrevisoes.data(ponto.getAsJsonObject().get("horario").getAsString());
                        if (!alvo.isBefore(inicio) && (!alvo.isAfter(fim) || origem.isAfter(atual))) pontos.add(ponto);
                    }
                    objeto.add("pontos", pontos);
                }
                mantidas.add(copia);
            }
        }
        return mantidas;
    }

    void preencherErros(JsonObject metrica, int indice, JsonArray versoes, CalculosPrevisoes.Serie serie) {
        double soma = 0;
        int quantidade = 0;
        LocalDateTime inicio = CalculosPrevisoes.inicioGrafico(serie.atual);
        for (JsonElement elemento : versoes) {
            JsonObject versao = elemento.getAsJsonObject();
            LocalDateTime gerada = CalculosPrevisoes.data(versao.get("gerada_em").getAsString());
            LocalDateTime origem = CalculosPrevisoes.data(versao.get("origem").getAsString());
            for (JsonElement ponto : versao.getAsJsonArray("metricas").get(indice).getAsJsonObject().getAsJsonArray("pontos")) {
                JsonObject previsto = ponto.getAsJsonObject();
                LocalDateTime alvo = CalculosPrevisoes.data(previsto.get("horario").getAsString());
                if (alvo.isBefore(inicio) || alvo.isAfter(serie.atual) || !gerada.isBefore(alvo) || !origem.isBefore(alvo)) continue;
                double real = serie.valor(indice, serie.indice(alvo));
                if (!Double.isFinite(real)) continue;
                double erro = Math.abs(real - previsto.get("valor").getAsDouble());
                soma += erro;
                quantidade++;
            }
        }
        JsonObject indicadores = metrica.getAsJsonObject("indicadores");
        CalculosPrevisoes.numero(indicadores, "erro_medio", quantidade == 0 ? null : soma / quantidade);
        indicadores.addProperty("comparacoes_erro", quantidade);
        indicadores.addProperty("criterio_erro", "Todas as versões arquivadas com alvo observado na janela do gráfico");
        indicadores.addProperty("erro_desde", CalculosPrevisoes.horario(inicio));
        indicadores.addProperty("erro_ate", CalculosPrevisoes.horario(serie.atual));
    }
}
