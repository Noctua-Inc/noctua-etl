package com.noctua.processamento.previsoes;


import com.google.gson.*;
import com.noctua.infraestrutura.banco.Mainframe;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
                LocalDateTime geracaoAtual = IndicadoresPrevisao.lerData(atual.getDados(), "gerado_em");
                LocalDateTime geracaoNova = IndicadoresPrevisao.lerData(novo, "gerado_em");
                if (dataAtual.equals(dataNova) && geracaoAtual != null) {
                    if (geracaoAtual.isAfter(geracaoNova)) return;
                    if (geracaoAtual.equals(geracaoNova) && mesmaPrevisao(atual.getDados(), novo)) return;
                }
            }
            IndicadoresPrevisao.preencherMainframe(novo, atual.getDados());
            if (Objects.equals(atual.getDados(), novo) || gravar(chave, novo, atual.getEtag())) return;
        } 
        throw new IOException("O documento da mainframe mudou durante o envio. Reexecute este evento.");
    }

    private boolean mesmaPrevisao(JsonObject anterior, JsonObject novo) {
        if (!versaoDois(anterior) || !Objects.equals(anterior.get("capturas_no_minuto"), novo.get("capturas_no_minuto"))) {
            return false;
        }
        for (int m = 0; m < CalculosPrevisoes.NOMES.length; m++) {
            JsonObject previa = metrica(anterior, m);
            JsonObject atual = metrica(novo, m);
            if (!previa.get("graficos").equals(atual.get("graficos"))
                    || !previa.get("limite_minimo").equals(atual.get("limite_minimo"))
                    || !previa.get("limite_maximo").equals(atual.get("limite_maximo"))) return false;
        }
        return true;
    }

    public void publicarResumo(String empresa, int idEmpresa, List<String> hostnames, LocalDateTime gerado) throws IOException {
        String chave = empresa + "/03-gold/previsoes/resumo.json";
        List<String> cadastradas = hostnames.stream().distinct().toList();
        for (int tentativa = 0; tentativa < 4; tentativa++) {
            ArquivoJson anterior = ler(chave);
            List<JsonObject> documentos = new ArrayList<>();
            Set<Integer> ids = new HashSet<>();
            LocalDateTime referencia = null;
            for (String hostname : cadastradas) {
                JsonObject documento = ler(empresa + "/03-gold/" + hostname + "/previsoes.json").getDados();
                if (!documento.has("componentes") || !ids.add(documento.get("id_mainframe").getAsInt())) continue;
                documentos.add(documento);
                LocalDateTime coleta = IndicadoresPrevisao.lerData(documento, "atualizado_em");
                if (referencia == null || coleta.isAfter(referencia)) referencia = coleta;
            }
            if (referencia == null) referencia = gerado.withSecond(0).withNano(0);
            JsonObject resumo = montarResumo(idEmpresa, cadastradas.size(), documentos, referencia, gerado, anterior.getDados());
            if (mesmoResumo(anterior.getDados(), resumo)) return;
            if (gravar(chave, resumo, anterior.getEtag())) return;
        }
        throw new IOException("O resumo mudou durante o envio. Reexecute este evento.");
    }

    private JsonObject montarResumo(int idEmpresa, int cadastradas, List<JsonObject> documentos,
                                    LocalDateTime referencia, LocalDateTime gerado, JsonObject anterior) {
        LocalDateTime inicio = IndicadoresPrevisao.passoSeguinte(referencia);
        LocalDateTime fim = CalculosPrevisoes.fimGrafico(referencia);
        boolean comparavel = versaoDois(anterior) && anterior.get("id_empresa").getAsInt() == idEmpresa;
        LocalDateTime geradoAnterior = comparavel ? IndicadoresPrevisao.lerData(anterior, "gerado_em") : null;
        LocalDateTime geracaoResumo = geradoAnterior != null && geradoAnterior.isAfter(gerado) ? geradoAnterior : gerado;
        JsonObject resumo = new JsonObject();
        resumo.addProperty("versao_schema", 2);
        resumo.addProperty("id_empresa", idEmpresa);
        resumo.addProperty("atualizado_em", CalculosPrevisoes.horario(referencia));
        resumo.addProperty("gerado_em", CalculosPrevisoes.horario(geracaoResumo));
        resumo.addProperty("valido_ate", CalculosPrevisoes.horario(fim));
        resumo.addProperty("fuso_horario", CalculosPrevisoes.FUSO.getId());
        JsonObject componentes = new JsonObject();
        componentes.add("cpu", new JsonObject());
        componentes.add("ram", new JsonObject());
        componentes.add("disco", new JsonObject());
        for (int m = 0; m < CalculosPrevisoes.NOMES.length; m++) {
            JsonObject resultado = IndicadoresPrevisao.resumirMetrica(documentos, m, cadastradas, inicio, fim,
                    geracaoResumo, comparavel ? metrica(anterior, m) : null, geradoAnterior);
            if (m < 2) {
                componentes.getAsJsonObject(CalculosPrevisoes.NOMES[m]).add("uso", resultado);
            } else {
                componentes.getAsJsonObject("disco").add(CalculosPrevisoes.NOMES[m], resultado);
            }
        }
        resumo.add("componentes", componentes);
        return resumo;
    }

    private boolean mesmoResumo(JsonObject anterior, JsonObject novo) {
        if (!versaoDois(anterior) || !Objects.equals(anterior.get("atualizado_em"), novo.get("atualizado_em"))) return false;
        for (int m = 0; m < CalculosPrevisoes.NOMES.length; m++) {
            JsonObject previa = metrica(anterior, m);
            JsonObject atual = metrica(novo, m);
            if (!Objects.equals(previa.get("mainframes"), atual.get("mainframes"))
                    || !Objects.equals(previa.get("cobertura"), atual.get("cobertura"))
                    || !previa.getAsJsonObject("indicadores").getAsJsonObject("mainframes_com_desvio").get("status")
                         .equals(atual.getAsJsonObject("indicadores").getAsJsonObject("mainframes_com_desvio").get("status"))) return false;
        }
        return true;
    }

    private static boolean versaoDois(JsonObject documento) {
        return documento.has("versao_schema") && documento.get("versao_schema").getAsInt() == 2;
    }
}
