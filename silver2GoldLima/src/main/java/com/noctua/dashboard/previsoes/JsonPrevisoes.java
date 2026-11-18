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
        disco.add("leitura", metricas[3]);
        disco.add("escrita", metricas[4]);
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
        return componentes.getAsJsonObject("disco").getAsJsonObject(CalculosPrevisoes.NOMES[m]);
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
                boolean jaIncluida = false;
                for (JsonElement existente : documentos) {
                    if (existente.getAsJsonObject().get("id_mainframe").getAsInt()
                            == documento.get("id_mainframe").getAsInt()) {
                        jaIncluida = true;
                        break;
                    }
                }
                if (jaIncluida) continue;
                documentos.add(documento);
                LocalDateTime data = CalculosPrevisoes.data(documento.get("atualizado_em").getAsString());
                if (referencia == null || data.isAfter(referencia)) referencia = data;
            }
            if (referencia == null) referencia = LocalDateTime.now(CalculosPrevisoes.FUSO).withSecond(0).withNano(0);
            JsonObject resumo = new JsonObject();
            resumo.addProperty("id_empresa", idEmpresa);
            resumo.addProperty("atualizado_em", CalculosPrevisoes.horario(referencia));
            LocalDateTime inicio = referencia.plusMinutes(10 - referencia.getMinute() % 10);
            LocalDateTime fim = CalculosPrevisoes.fimGrafico(referencia);
            int minutos = (int) Duration.between(inicio, fim).toMinutes();
            JsonObject componentes = new JsonObject();
            for (int m = 0; m < 5; m++) {
                JsonObject grupo = m < 3 ? new JsonObject() : componentes.getAsJsonObject("disco");
                JsonObject resultado = new JsonObject();
                resultado.addProperty("unidade", m < 3 ? "%" : "MiB/s");
                JsonArray linhas = new JsonArray();
                int comPrevisao = 0, comDesvio = 0;
                int[] concentracao = new int[minutos];
                for (JsonElement elemento : documentos) {
                    JsonObject documento = elemento.getAsJsonObject();
                    JsonObject metrica = metrica(documento, m);
                    JsonArray previsao = metrica.getAsJsonObject("graficos").getAsJsonArray("previsao");
                    Double[] valores = new Double[minutos + 1];
                    boolean cobrePeriodo = true;
                    Double menor = null, maior = null;
                    for (int i = 0; i <= minutos; i++) {
                        valores[i] = valorPrevisto(previsao, inicio.plusMinutes(i));
                        if (valores[i] == null) {
                            cobrePeriodo = false;
                        } else {
                            menor = menor == null ? valores[i] : Math.min(menor, valores[i]);
                            maior = maior == null ? valores[i] : Math.max(maior, valores[i]);
                        }
                    }
                    if (!cobrePeriodo) {
                        menor = null;
                        maior = null;
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
                        comPrevisao++;
                        if (abaixo || acima) comDesvio++;
                        double minimo = metrica.get("limite_minimo").getAsDouble();
                        double maximo = metrica.get("limite_maximo").getAsDouble();
                        // Cada posição representa [minuto, minuto seguinte), mesmo ao cruzar o limite entre pontos.
                        for (int i = 0; i < minutos; i++) {
                            if (Math.min(valores[i], valores[i + 1]) < minimo
                                    || Math.max(valores[i], valores[i + 1]) > maximo) {
                                concentracao[i]++;
                            }
                        }
                    }
                    linha.addProperty("situacao", situacao);
                    linhas.add(linha);
                }
                resultado.add("mainframes", linhas);
                resultado.add("indicadores", indicadoresEmpresa(inicio, fim, comPrevisao, comDesvio, concentracao));
                if (m < 2) {
                    grupo.add("uso", resultado);
                } else {
                    grupo.add(CalculosPrevisoes.NOMES[m], resultado);
                }
                componentes.add(new String[]{"cpu", "ram", "disco", "disco", "disco"}[m], grupo);
            }
            resumo.add("componentes", componentes);
            if (gravar(chave, resumo, anterior.getEtag())) return;
        }
        throw new IOException("O resumo mudou durante o envio. Reexecute este evento.");
    }

    private Double valorPrevisto(JsonArray pontos, LocalDateTime horario) {
        JsonObject anterior = null;
        for (JsonElement elemento : pontos) {
            JsonObject ponto = elemento.getAsJsonObject();
            LocalDateTime data = CalculosPrevisoes.data(ponto.get("horario").getAsString());
            if (data.equals(horario)) {
                return ponto.get("valor").isJsonNull() ? null : ponto.get("valor").getAsDouble();
            }
            if (data.isAfter(horario)) {
                if (anterior == null || anterior.get("valor").isJsonNull() || ponto.get("valor").isJsonNull()) return null;
                LocalDateTime inicio = CalculosPrevisoes.data(anterior.get("horario").getAsString());
                double fracao = (double) Duration.between(inicio, horario).toSeconds()
                        / Duration.between(inicio, data).toSeconds();
                double primeiro = anterior.get("valor").getAsDouble();
                return primeiro + (ponto.get("valor").getAsDouble() - primeiro) * fracao;
            }
            anterior = ponto;
        }
        return null;
    }

    private JsonObject indicadoresEmpresa(LocalDateTime inicio, LocalDateTime fim, int comPrevisao,
                                         int comDesvio, int[] concentracao) {
        JsonObject indicadores = new JsonObject();
        indicadores.addProperty("inicio_previsao", CalculosPrevisoes.horario(inicio));
        indicadores.addProperty("fim_previsao", CalculosPrevisoes.horario(fim));
        indicadores.addProperty("mainframes_com_previsao", comPrevisao);
        indicadores.addProperty("mainframes_com_desvio", comDesvio);
        int maior = 0, primeiro = 0, ultimo = 0;
        for (int i = 0; i < concentracao.length; i++) {
            int quantidade = concentracao[i];
            int finalIntervalo = i + 1;
            while (finalIntervalo < concentracao.length && concentracao[finalIntervalo] == quantidade) finalIntervalo++;
            if (quantidade > maior) {
                maior = quantidade;
                primeiro = i;
                ultimo = finalIntervalo;
            }
            i = finalIntervalo - 1;
        }
        if (maior == 0) {
            indicadores.add("maior_concentracao", JsonNull.INSTANCE);
        } else {
            JsonObject intervalo = new JsonObject();
            intervalo.addProperty("inicio", CalculosPrevisoes.horario(inicio.plusMinutes(primeiro)));
            intervalo.addProperty("fim", CalculosPrevisoes.horario(inicio.plusMinutes(ultimo)));
            intervalo.addProperty("quantidade", maior);
            indicadores.add("maior_concentracao", intervalo);
        }
        return indicadores;
    }
}
