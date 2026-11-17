package com.noctua.dashboard.previsoes;


import com.google.gson.*;
import com.noctua.model.Mainframe;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;

public class JsonPrevisoes {
    private final S3Client s3;
    private final String bucket;
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    public JsonPrevisoes(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    private ArquivoJson ler(String chave) throws IOException {
        try (ResponseInputStream<GetObjectResponse> entrada = s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(chave).build());
             InputStreamReader conversor = new InputStreamReader(entrada, StandardCharsets.UTF_8);
             BufferedReader leitor = new BufferedReader(conversor)) {
            return new ArquivoJson(JsonParser.parseReader(leitor).getAsJsonObject(), entrada.response().eTag());
        } catch (NoSuchKeyException erro) {
            return new ArquivoJson(new JsonObject(), null);
        }
    }

    private boolean gravar(String chave, JsonObject dados, String etag) {
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

    public JsonObject criarDocumento(int idEmpresa, Mainframe mainframe, LocalDateTime atualizado, int capturasNoMinuto,
                                    LocalDateTime gerado, JsonObject[] metricas) {
        JsonObject documento = new JsonObject();
        documento.addProperty("id_empresa", idEmpresa);
        documento.addProperty("id_mainframe", mainframe.getIdMainframe());
        documento.addProperty("hostname", mainframe.getHostname());
        documento.addProperty("atualizado_em", CalculosPrevisoes.horario(atualizado));
        documento.addProperty("gerado_em", CalculosPrevisoes.horario(gerado));
        documento.addProperty("capturas_no_minuto", capturasNoMinuto);
        JsonObject componentes = new JsonObject();
        JsonObject cpu = new JsonObject();
        JsonObject ram = new JsonObject();
        JsonObject disco = new JsonObject();
        cpu.add("uso", metricas[0]);
        ram.add("uso", metricas[1]);
        disco.add("ocupacao", metricas[2]);
        disco.add("leitura", metricaSemCapturas(atualizado));
        disco.add("escrita", metricaSemCapturas(atualizado));
        componentes.add("cpu", cpu);
        componentes.add("ram", ram);
        componentes.add("disco", disco);
        documento.add("componentes", componentes);
        return documento;
    }

    public static JsonObject metrica(JsonObject documento, int m) {
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
        for (String nome : new String[]{"maior_previsto", "menor_previsto", "primeira_saida_em_minutos", "primeira_saida_tipo"}) {
            indicadores.add(nome, JsonNull.INSTANCE);
        }
        resultado.add("indicadores", indicadores);
        JsonObject graficos = new JsonObject();
        graficos.add("atual", CalculosPrevisoes.ponto(atual, null));
        for (String nome : new String[]{"historico", "previsao", "mapa"}) graficos.add(nome, new JsonArray());
        resultado.add("graficos", graficos);
        resultado.addProperty("motivo", "O Silver ainda não fornece leitura e escrita globais do disco.");
        return resultado;
    }

    public void publicar(String chave, JsonObject novo) throws IOException {
        for (int tentativa = 0; tentativa < 4; tentativa++) {
            ArquivoJson atual = ler(chave);
            if (atual.getDados().has("atualizado_em")) {
                LocalDateTime dataAtual = CalculosPrevisoes.data(atual.getDados().get("atualizado_em").getAsString());
                LocalDateTime dataNova = CalculosPrevisoes.data(novo.get("atualizado_em").getAsString());
                if (dataAtual.isAfter(dataNova)) return;
                if (dataAtual.equals(dataNova) && atual.getDados().has("capturas_no_minuto")
                        && atual.getDados().get("capturas_no_minuto").getAsInt() > novo.get("capturas_no_minuto").getAsInt()) return;
                if (dataAtual.equals(dataNova) && CalculosPrevisoes.data(atual.getDados().get("gerado_em").getAsString())
                        .isAfter(CalculosPrevisoes.data(novo.get("gerado_em").getAsString()))) return;
            }
            if (gravar(chave, novo, atual.getEtag())) return;
        }
        throw new IOException("O documento da mainframe mudou durante o envio. Reexecute este evento.");
    }

    public void publicarResumo(String empresa, int idEmpresa, List<String> hostnames) throws IOException {
        String chave = empresa + "/03-gold/previsoes/resumo.json";
        for (int tentativa = 0; tentativa < 4; tentativa++) {
            ArquivoJson anterior = ler(chave);
            JsonArray documentos = new JsonArray();
            LocalDateTime referencia = null;
            for (String hostname : hostnames) {
                JsonObject documento = ler(empresa + "/03-gold/" + hostname + "/previsoes.json").getDados();
                if (!documento.has("componentes")) continue;
                documentos.add(documento);
                LocalDateTime data = CalculosPrevisoes.data(documento.get("atualizado_em").getAsString());
                if (referencia == null || data.isAfter(referencia)) referencia = data;
            }
            if (referencia == null) return;
            JsonObject resumo = new JsonObject();
            resumo.addProperty("id_empresa", idEmpresa);
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
                            if (menor == null) {
                                menor = valor;
                            } else {
                                menor = Math.min(menor, valor);
                            }
                            if (maior == null) {
                                maior = valor;
                            } else {
                                maior = Math.max(maior, valor);
                            }
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
                        if (abaixo && acima) {
                            situacao = "Abaixo e acima";
                        } else if (abaixo) {
                            situacao = "Abaixo do mínimo";
                        } else if (acima) {
                            situacao = "Acima do máximo";
                        } else {
                            situacao = "Dentro da faixa";
                        }
                    }
                    linha.addProperty("situacao", situacao);
                    linhas.add(linha);
                }
                resultado.add("mainframes", linhas);
                if (m < 2) {
                    grupo.add("uso", resultado);
                } else {
                    grupo.add("ocupacao", resultado);
                }
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
            if (gravar(chave, resumo, anterior.getEtag())) return;
        }
        throw new IOException("O resumo mudou durante o envio. Reexecute este evento.");
    }
}
