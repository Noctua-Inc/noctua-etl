package com.noctua.fetching.bucket;

import com.google.gson.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PrevisoesGoldTest {
    static final String SILVER = "sptech/02-silver/MF/MF_historico.csv";
    static final String GOLD = "sptech/03-gold/MF/";
    static final String ARQUIVO = GOLD + "historico-previsoes/previsoes.json";
    static final String CAB = "Timestamp,HostName,Quantidade_Capturas,Porcentagem_CPU,Porcentagem_RAM,Porcentagem_Disco\n";

    static class S3Memoria {
        Map<String, byte[]> objetos = new HashMap<>();
        Map<String, String> etags = new HashMap<>();
        int versao;
        int conflitos;
        S3Client cliente = (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(), new Class<?>[]{S3Client.class}, (p, metodo, args) -> {
            if (metodo.getName().equals("close")) return null;
            if (metodo.getName().equals("getObject")) {
                String chave = ((GetObjectRequest) args[0]).key();
                if (!objetos.containsKey(chave)) throw NoSuchKeyException.builder().message("Ausente").statusCode(404).build();
                return new ResponseInputStream<>(GetObjectResponse.builder().eTag(etags.get(chave)).build(),
                        AbortableInputStream.create(new ByteArrayInputStream(objetos.get(chave))));
            }
            if (metodo.getName().equals("putObject")) {
                PutObjectRequest pedido = (PutObjectRequest) args[0];
                if (conflitos-- > 0) throw S3Exception.builder().statusCode(412).build();
                Map<String, List<String>> headers = pedido.overrideConfiguration().orElseThrow().headers();
                if (headers.containsKey("If-None-Match") && objetos.containsKey(pedido.key())) throw S3Exception.builder().statusCode(412).build();
                if (headers.containsKey("If-Match") && !headers.get("If-Match").get(0).equals(etags.get(pedido.key()))) {
                    throw S3Exception.builder().statusCode(412).build();
                }
                RequestBody corpo = (RequestBody) args[1];
                try (var entrada = corpo.contentStreamProvider().newStream()) {
                    colocar(pedido.key(), entrada.readAllBytes());
                }
                return PutObjectResponse.builder().eTag(etags.get(pedido.key())).build();
            }
            throw new UnsupportedOperationException(metodo.getName());
        });

        void colocar(String chave, byte[] dados) {
            objetos.put(chave, dados);
            etags.put(chave, "v" + ++versao);
        }

        JsonObject json(String chave) {
            return JsonParser.parseString(new String(objetos.get(chave), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    JdbcTemplate banco() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE empresa(id_empresa INT, razao_social VARCHAR(100))");
        jdbc.execute("CREATE TABLE mainframe(id_mainframe INT, hostname VARCHAR(100), fk_empresa INT, status VARCHAR(30))");
        jdbc.execute("CREATE TABLE componente(id_componente INT, tipo VARCHAR(30))");
        jdbc.execute("CREATE TABLE parametro(id_mainframe INT, id_componente INT, pico_min INT, pico_max INT)");
        jdbc.update("INSERT INTO empresa VALUES (1, 'SPTech'), (2, 'Outra')");
        jdbc.update("INSERT INTO mainframe VALUES (10, 'MF', 1, 'ativo'), (20, 'MF', 2, 'ativo')");
        jdbc.update("INSERT INTO componente VALUES (1, 'CPU'), (2, 'RAM'), (3, 'DISCO')");
        jdbc.update("INSERT INTO parametro VALUES (10, 1, 30, 80), (10, 2, 20, 90), (10, 3, 10, 95), (20, 1, 0, 10)");
        return jdbc;
    }

    Clock relogio(String horario) {
        return Clock.fixed(OffsetDateTime.parse(horario + "-03:00").toInstant(), CalculosPrevisoes.FUSO);
    }

    void executar(S3Memoria s3, JdbcTemplate jdbc, String hora, String linhas) throws Exception {
        s3.colocar(SILVER, (CAB + linhas).getBytes(StandardCharsets.UTF_8));
        new Previsoes(relogio(hora), s3.cliente, jdbc, "teste").executar(SILVER);
    }

    @Test
    void publicaEComparaTodasAsVersoesReaisSemPerderHistorico() throws Exception {
        S3Memoria s3 = new S3Memoria();
        JdbcTemplate jdbc = banco();
        String a = "2026-11-11 14:37:00,MF,20,40,50,60\n";
        executar(s3, jdbc, "2026-11-11T14:37:10", a);
        JsonObject primeiro = s3.json(GOLD + "previsoes.json");
        assertEquals(10, primeiro.get("id_mainframe").getAsInt());
        JsonObject cpu = Previsoes.metrica(primeiro, 0);
        JsonObject pontoPrevisto = cpu.getAsJsonObject("graficos").getAsJsonArray("previsao").get(0).getAsJsonObject();
        assertFalse(cpu.has("faixa"));
        assertFalse(pontoPrevisto.has("inferior"));
        assertFalse(pontoPrevisto.has("superior"));
        assertTrue(Previsoes.metrica(primeiro, 0).getAsJsonObject("indicadores").get("erro_medio").isJsonNull());
        assertEquals("2026-11-11T14:40-03:00", Previsoes.metrica(primeiro, 0).getAsJsonObject("graficos")
                .getAsJsonArray("previsao").get(0).getAsJsonObject().get("horario").getAsString());

        String b = a + "2026-11-11 14:40:00,MF,20,60,50,60\n2026-11-11 14:47:00,MF,20,60,50,60\n";
        executar(s3, jdbc, "2026-11-11T14:47:10", b);
        assertEquals(20, Previsoes.metrica(s3.json(GOLD + "previsoes.json"), 0).getAsJsonObject("indicadores").get("erro_medio").getAsDouble());

        String c = b + "2026-11-11 14:50:00,MF,20,90,50,60\n2026-11-11 14:57:00,MF,20,90,50,60\n";
        executar(s3, jdbc, "2026-11-11T14:57:10", c);
        JsonObject indicadores = Previsoes.metrica(s3.json(GOLD + "previsoes.json"), 0).getAsJsonObject("indicadores");
        assertEquals(3, indicadores.get("comparacoes_erro").getAsInt());
        assertEquals(33.3333, indicadores.get("erro_medio").getAsDouble(), 0.0001);
        executar(s3, jdbc, "2026-11-11T14:57:20", c);
        assertEquals(3, s3.json(ARQUIVO).getAsJsonArray("versoes").size());
        assertEquals(1, s3.json("sptech/03-gold/previsoes/resumo.json").getAsJsonObject("componentes")
                .getAsJsonObject("cpu").getAsJsonObject("uso").getAsJsonArray("mainframes").size());
    }

    @Test
    void limpaJanelaEVersaoAntigaNaoSobrescreveDocumentoNovo() throws Exception {
        S3Memoria s3 = new S3Memoria();
        JdbcTemplate jdbc = banco();
        String antigo = "2026-11-11 14:37:00,MF,2,40,50,60\n";
        executar(s3, jdbc, "2026-11-11T14:37:10", antigo);
        executar(s3, jdbc, "2026-11-11T21:03:10", antigo + "2026-11-11 21:03:00,MF,2,50,60,70\n");
        assertEquals(1, s3.json(ARQUIVO).getAsJsonArray("versoes").size());
        executar(s3, jdbc, "2026-11-11T21:04:10", antigo);
        assertEquals("2026-11-11T21:03-03:00", s3.json(GOLD + "previsoes.json").get("atualizado_em").getAsString());
        assertEquals(1, s3.json(ARQUIVO).getAsJsonArray("versoes").size());
    }

    @Test
    void cruzaMeiaNoiteEPreservaZerosELacunas() throws Exception {
        S3Memoria s3 = new S3Memoria();
        JdbcTemplate jdbc = banco();
        executar(s3, jdbc, "2026-11-11T23:37:10", "2026-11-11 23:37:00,MF,2,0,0,0\n");
        JsonObject metrica = Previsoes.metrica(s3.json(GOLD + "previsoes.json"), 0);
        JsonArray futuro = metrica.getAsJsonObject("graficos").getAsJsonArray("previsao");
        assertEquals("2026-11-12T05:00-03:00", futuro.get(futuro.size() - 1).getAsJsonObject().get("horario").getAsString());
        assertEquals(0, futuro.get(0).getAsJsonObject().get("valor").getAsDouble());
        assertTrue(metrica.getAsJsonObject("graficos").getAsJsonArray("historico").get(0).getAsJsonObject().get("valor").isJsonNull());
    }

    @Test
    void repeteEnvioCondicionalQuandoHaConflito() throws Exception {
        S3Memoria s3 = new S3Memoria();
        s3.conflitos = 1;
        executar(s3, banco(), "2026-11-11T14:37:10", "2026-11-11 14:37:00,MF,2,40,50,60\n");
        assertEquals(1, s3.json(ARQUIVO).getAsJsonArray("versoes").size());
    }

    @Test
    void processaUmaSemanaComPadraoDiario() {
        ArrayList<CalculosPrevisoes.Leitura> leituras = new ArrayList<>();
        LocalDateTime inicio = LocalDateTime.of(2026, 11, 4, 14, 37);
        for (int i = 0; i <= 7 * 1440; i++) {
            LocalDateTime hora = inicio.plusMinutes(i);
            double cpu = 45 + 20 * Math.sin(2 * Math.PI * (hora.getHour() * 60 + hora.getMinute()) / 1440.0);
            leituras.add(new CalculosPrevisoes.Leitura(hora, 20, cpu, 50, 60));
        }
        CalculosPrevisoes.Serie serie = new CalculosPrevisoes.Serie(leituras);
        JsonObject resultado = new CalculosPrevisoes().calcular(serie, 0, 30.0, 80.0, serie.atual.plusSeconds(10));
        assertEquals("Perfil diário", resultado.get("modelo").getAsString());
        assertEquals(33, resultado.getAsJsonObject("graficos").getAsJsonArray("previsao").size());
        assertEquals(168, resultado.getAsJsonObject("graficos").getAsJsonArray("mapa").size());
    }
}
