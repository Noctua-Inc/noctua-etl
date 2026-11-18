package com.noctua.dashboard.previsoes;


import com.google.gson.*;
import com.noctua.model.ConsultasPrevisoes;
import com.noctua.model.Limite;
import com.noctua.model.Mainframe;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PrevisoesGoldTest {
    static final String SILVER = "sptech/02-silver/MF/MF_historico.csv";
    static final String GOLD = "sptech/03-gold/MF/";
    static final String ARQUIVO = GOLD + "historico-previsoes/previsoes.json";
    static final String CAB = "Timestamp,HostName,Quantidade_Capturas,Porcentagem_CPU,Porcentagem_RAM,Porcentagem_Disco,Leitura_Disco_MiB_s,Escrita_Disco_MiB_s\n";

    static class S3Memoria {
        Map<String, byte[]> objetos = new HashMap<>();
        Map<String, String> etags = new HashMap<>();
        Set<String> leituras = new HashSet<>();
        Map<String, Integer> tentativas = new HashMap<>();
        int versao;
        int conflitos;
        int conflitosResumo;
        int statusConflito = 412;
        String chaveConcorrente;
        JsonObject documentoConcorrente;
        S3Client cliente = (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(), new Class<?>[]{S3Client.class}, (p, metodo, args) -> {
            if (metodo.getName().equals("close")) return null;
            if (metodo.getName().equals("getObject")) {
                String chave = ((GetObjectRequest) args[0]).key();
                leituras.add(chave);
                if (!objetos.containsKey(chave)) throw NoSuchKeyException.builder().message("Ausente").statusCode(404).build();
                return new ResponseInputStream<>(GetObjectResponse.builder().eTag(etags.get(chave)).build(),
                        AbortableInputStream.create(new ByteArrayInputStream(objetos.get(chave))));
            }
            if (metodo.getName().equals("putObject")) {
                PutObjectRequest pedido = (PutObjectRequest) args[0];
                tentativas.merge(pedido.key(), 1, Integer::sum);
                assertEquals("application/json; charset=utf-8", pedido.contentType());
                if (conflitos-- > 0) throw S3Exception.builder().statusCode(statusConflito).build();
                if (pedido.key().equals("sptech/03-gold/previsoes/resumo.json") && conflitosResumo-- > 0) {
                    throw S3Exception.builder().statusCode(statusConflito).build();
                }
                if (pedido.key().equals(chaveConcorrente)) {
                    colocar(chaveConcorrente, documentoConcorrente.toString().getBytes(StandardCharsets.UTF_8));
                    chaveConcorrente = null;
                }
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
    void consultaEmpresaPeloPrefixoEMainframeDentroDaEmpresa() {
        JdbcTemplate jdbc = banco();
        jdbc.update("UPDATE empresa SET razao_social = ? WHERE id_empresa = ?", "SP Tech", 1);
        ConsultasPrevisoes consultas = new ConsultasPrevisoes(jdbc);

        assertEquals(Integer.valueOf(1), consultas.buscarIdEmpresa("sp_tech"));
        assertEquals(Integer.valueOf(10), consultas.buscarIdMainframe("MF", 1));
        assertEquals(Integer.valueOf(20), consultas.buscarIdMainframe("MF", 2));
        assertThrows(EmptyResultDataAccessException.class, () -> consultas.buscarIdEmpresa("' OR 1=1 --"));
        assertThrows(EmptyResultDataAccessException.class, () -> consultas.buscarIdMainframe("' OR 1=1 --", 1));
        assertThrows(EmptyResultDataAccessException.class, () -> consultas.buscarIdMainframe("MF", 3));
    }

    @Test
    void consultaMainframeComHostnameELimitesDaEmpresaCorreta() {
        ConsultasPrevisoes consultas = new ConsultasPrevisoes(banco());

        Mainframe mainframe = consultas.buscarMainframe("MF", 1);
        assertEquals(10, mainframe.getIdMainframe());
        assertEquals("MF", mainframe.getHostname());
        assertEquals(List.of("CPU", "DISCO", "RAM"), mainframe.getLimites().stream().map(Limite::getTipo).toList());
        assertEquals(Integer.valueOf(30), mainframe.getLimites().get(0).getPicoMin());
        assertEquals(Integer.valueOf(80), mainframe.getLimites().get(0).getPicoMax());

        Mainframe outraEmpresa = consultas.buscarMainframe("MF", 2);
        assertEquals(20, outraEmpresa.getIdMainframe());
        assertEquals("MF", outraEmpresa.getHostname());
        assertEquals(1, outraEmpresa.getLimites().size());
        assertEquals("CPU", outraEmpresa.getLimites().get(0).getTipo());
        assertEquals(Integer.valueOf(0), outraEmpresa.getLimites().get(0).getPicoMin());
        assertEquals(Integer.valueOf(10), outraEmpresa.getLimites().get(0).getPicoMax());
        assertThrows(EmptyResultDataAccessException.class, () -> consultas.buscarMainframe("MF", 3));
    }

    @Test
    void consultaLimitesDaMainframeOrdenadosPreservandoNulosEZeros() {
        JdbcTemplate jdbc = banco();
        jdbc.update("UPDATE parametro SET pico_min = NULL, pico_max = 0 WHERE id_mainframe = 10 AND id_componente = 3");
        ConsultasPrevisoes consultas = new ConsultasPrevisoes(jdbc);

        List<Limite> limites = consultas.buscarLimites(10);
        assertEquals(List.of("CPU", "DISCO", "RAM"), limites.stream().map(Limite::getTipo).toList());
        assertEquals(Integer.valueOf(30), limites.get(0).getPicoMin());
        assertEquals(Integer.valueOf(80), limites.get(0).getPicoMax());
        assertNull(limites.get(1).getPicoMin());
        assertEquals(Integer.valueOf(0), limites.get(1).getPicoMax());
        assertEquals(Integer.valueOf(20), limites.get(2).getPicoMin());
        assertEquals(Integer.valueOf(90), limites.get(2).getPicoMax());
        assertTrue(consultas.buscarLimites(999).isEmpty());
    }

    @Test
    void consultaHostnamesDoResumoPorEmpresaEStatusNaOrdemDosIds() {
        JdbcTemplate jdbc = banco();
        jdbc.update("""
                INSERT INTO mainframe VALUES
                (5, 'PRIMEIRO', 1, 'ativo'), (30, 'ULTIMO', 1, 'manutencao'),
                (2, 'INATIVO', 1, 'inativo'), (3, 'SEM_STATUS', 1, NULL), (1, 'OUTRA_EMPRESA', 2, 'ativo')
                """);
        ConsultasPrevisoes consultas = new ConsultasPrevisoes(jdbc);

        assertEquals(List.of("PRIMEIRO", "MF", "ULTIMO"), consultas.buscarHostnamesResumo(1));
        assertEquals(List.of("OUTRA_EMPRESA", "MF"), consultas.buscarHostnamesResumo(2));
        assertTrue(consultas.buscarHostnamesResumo(999).isEmpty());
    }

    @Test
    void leCsvComBomECrLfSemAcessarArquivoDePrevisoesAnteriores() throws Exception {
        S3Memoria s3 = new S3Memoria();
        JdbcTemplate jdbc = banco();
        String csv = "\uFEFF" + CAB.replace("\n", "\r\n") + "2026-11-11 14:37:00,MF,2,40,50,60,120.0,240.0\r\n\r\n";
        s3.colocar(SILVER, csv.getBytes(StandardCharsets.UTF_8));
        s3.colocar(ARQUIVO, "arquivo antigo que não deve ser lido".getBytes(StandardCharsets.UTF_8));

        new Previsoes(relogio("2026-11-11T14:37:10"), s3.cliente, jdbc, "teste").executar(SILVER);
        new Previsoes(relogio("2026-11-11T14:37:20"), s3.cliente, jdbc, "teste").executar(SILVER);

        assertEquals("2026-11-11T14:37:20-03:00", s3.json(GOLD + "previsoes.json").get("gerado_em").getAsString());
        assertFalse(s3.leituras.contains(ARQUIVO));
        assertFalse(s3.tentativas.containsKey(ARQUIVO));
        assertEquals("arquivo antigo que não deve ser lido", new String(s3.objetos.get(ARQUIVO), StandardCharsets.UTF_8));
    }

    @Test
    void publicaDocumentosDaMainframeEResumoSemArquivoOuIndicadoresDeErro() throws Exception {
        S3Memoria s3 = new S3Memoria();
        JdbcTemplate jdbc = banco();
        String a = "2026-11-11 14:37:00,MF,20,40,50,60,120.0,240.0\n";
        executar(s3, jdbc, "2026-11-11T14:37:10", a);
        JsonObject primeiro = s3.json(GOLD + "previsoes.json");
        assertEquals(1, primeiro.get("id_empresa").getAsInt());
        assertEquals(10, primeiro.get("id_mainframe").getAsInt());
        assertEquals("MF", primeiro.get("hostname").getAsString());
        JsonObject cpu = JsonPrevisoes.metrica(primeiro, 0);
        assertEquals(30, cpu.get("limite_minimo").getAsInt());
        assertEquals(80, cpu.get("limite_maximo").getAsInt());
        assertEquals(20, JsonPrevisoes.metrica(primeiro, 1).get("limite_minimo").getAsInt());
        assertEquals(90, JsonPrevisoes.metrica(primeiro, 1).get("limite_maximo").getAsInt());
        assertEquals(10, JsonPrevisoes.metrica(primeiro, 2).get("limite_minimo").getAsInt());
        assertEquals(95, JsonPrevisoes.metrica(primeiro, 2).get("limite_maximo").getAsInt());
        JsonObject pontoPrevisto = cpu.getAsJsonObject("graficos").getAsJsonArray("previsao").get(0).getAsJsonObject();
        assertFalse(cpu.has("faixa"));
        assertFalse(pontoPrevisto.has("inferior"));
        assertFalse(pontoPrevisto.has("superior"));
        assertEquals("2026-11-11T14:40-03:00", JsonPrevisoes.metrica(primeiro, 0).getAsJsonObject("graficos")
                .getAsJsonArray("previsao").get(0).getAsJsonObject().get("horario").getAsString());

        String b = a + "2026-11-11 14:40:00,MF,20,60,50,60,120.0,240.0\n2026-11-11 14:47:00,MF,20,60,50,60,120.0,240.0\n";
        executar(s3, jdbc, "2026-11-11T14:47:10", b);
        assertEquals("2026-11-11T14:47-03:00", s3.json(GOLD + "previsoes.json").get("atualizado_em").getAsString());

        String c = b + "2026-11-11 14:50:00,MF,20,90,50,60,120.0,240.0\n2026-11-11 14:57:00,MF,20,90,50,60,120.0,240.0\n";
        executar(s3, jdbc, "2026-11-11T14:57:10", c);
        executar(s3, jdbc, "2026-11-11T14:57:20", c);
        JsonObject documento = s3.json(GOLD + "previsoes.json");
        assertEquals("2026-11-11T14:57-03:00", documento.get("atualizado_em").getAsString());
        for (int m = 0; m < 3; m++) {
            JsonObject indicadores = JsonPrevisoes.metrica(documento, m).getAsJsonObject("indicadores");
            assertFalse(indicadores.has("erro_medio"));
            assertFalse(indicadores.has("unidade_erro"));
            assertFalse(indicadores.has("comparacoes_erro"));
            assertTrue(indicadores.has("maior_previsto"));
        }
        JsonObject disco = documento.getAsJsonObject("componentes").getAsJsonObject("disco");
        for (int m = 3; m < 5; m++) {
            JsonObject taxa = JsonPrevisoes.metrica(documento, m);
            assertEquals("MiB/s", taxa.get("unidade").getAsString());
            assertEquals(120.0 * (m - 2), taxa.getAsJsonObject("graficos").getAsJsonObject("atual").get("valor").getAsDouble());
            assertEquals(120.0 * (m - 2), taxa.getAsJsonObject("graficos").getAsJsonArray("previsao")
                    .get(0).getAsJsonObject().get("valor").getAsDouble());
            JsonObject resumoTaxa = s3.json("sptech/03-gold/previsoes/resumo.json").getAsJsonObject("componentes")
                    .getAsJsonObject("disco").getAsJsonObject(CalculosPrevisoes.NOMES[m]);
            assertEquals("MiB/s", resumoTaxa.get("unidade").getAsString());
            assertEquals(120.0 * (m - 2), resumoTaxa.getAsJsonArray("mainframes").get(0)
                    .getAsJsonObject().get("maior_previsto").getAsDouble());
            assertEquals(0, resumoTaxa.getAsJsonObject("indicadores").get("mainframes_com_previsao").getAsInt());
        }
        assertFalse(disco.getAsJsonObject("leitura").getAsJsonObject("indicadores").has("erro_medio"));
        assertFalse(disco.getAsJsonObject("escrita").getAsJsonObject("indicadores").has("unidade_erro"));
        assertFalse(s3.objetos.containsKey(ARQUIVO));
        assertFalse(s3.leituras.contains(ARQUIVO));
        assertEquals(1, s3.json("sptech/03-gold/previsoes/resumo.json").getAsJsonObject("componentes")
                .getAsJsonObject("cpu").getAsJsonObject("uso").getAsJsonArray("mainframes").size());
    }

    @Test
    void eventoAntigoNaoSobrescreveDocumentoNovo() throws Exception {
        S3Memoria s3 = new S3Memoria();
        JdbcTemplate jdbc = banco();
        String antigo = "2026-11-11 14:37:00,MF,2,40,50,60,120.0,240.0\n";
        executar(s3, jdbc, "2026-11-11T14:37:10", antigo);
        executar(s3, jdbc, "2026-11-11T21:03:10", antigo + "2026-11-11 21:03:00,MF,2,50,60,70,120.0,240.0\n");
        JsonObject novo = s3.json(GOLD + "previsoes.json");
        executar(s3, jdbc, "2026-11-11T21:04:10", antigo);
        assertEquals("2026-11-11T21:03-03:00", s3.json(GOLD + "previsoes.json").get("atualizado_em").getAsString());
        assertEquals(novo, s3.json(GOLD + "previsoes.json"));
        assertFalse(s3.objetos.containsKey(ARQUIVO));
    }

    @Test
    void cruzaMeiaNoiteEPreservaZerosELacunas() throws Exception {
        S3Memoria s3 = new S3Memoria();
        JdbcTemplate jdbc = banco();
        executar(s3, jdbc, "2026-11-11T23:37:10", "2026-11-11 23:37:00,MF,2,0,0,0,120.0,240.0\n");
        JsonObject metrica = JsonPrevisoes.metrica(s3.json(GOLD + "previsoes.json"), 0);
        JsonArray futuro = metrica.getAsJsonObject("graficos").getAsJsonArray("previsao");
        assertEquals("2026-11-12T05:00-03:00", futuro.get(futuro.size() - 1).getAsJsonObject().get("horario").getAsString());
        assertEquals(0, futuro.get(0).getAsJsonObject().get("valor").getAsDouble());
        assertTrue(metrica.getAsJsonObject("graficos").getAsJsonArray("historico").get(0).getAsJsonObject().get("valor").isJsonNull());
        JsonObject indicadores = JsonPrevisoes.metrica(s3.json("sptech/03-gold/previsoes/resumo.json"), 0)
                .getAsJsonObject("indicadores");
        assertEquals("2026-11-11T23:40-03:00", indicadores.get("inicio_previsao").getAsString());
        assertEquals("2026-11-12T05:00-03:00", indicadores.get("fim_previsao").getAsString());
        assertEquals(1, indicadores.get("mainframes_com_desvio").getAsInt());
    }

    @ParameterizedTest
    @ValueSource(ints = {409, 412})
    void repeteEnvioCondicionalQuandoHaConflito(int status) throws Exception {
        S3Memoria s3 = new S3Memoria();
        s3.conflitos = 1;
        s3.statusConflito = status;
        executar(s3, banco(), "2026-11-11T14:37:10", "2026-11-11 14:37:00,MF,2,40,50,60,120.0,240.0\n");
        assertEquals(Integer.valueOf(2), s3.tentativas.get(GOLD + "previsoes.json"));
        assertTrue(s3.objetos.containsKey("sptech/03-gold/previsoes/resumo.json"));
        assertFalse(s3.objetos.containsKey(ARQUIVO));
    }

    @Test
    void mesmoMinutoComMenosCapturasNaoSobrescreveDocumentoMaisCompleto() throws Exception {
        S3Memoria s3 = new S3Memoria();
        JdbcTemplate jdbc = banco();
        executar(s3, jdbc, "2026-11-11T14:37:10", "2026-11-11 14:37:00,MF,20,40,50,60,120.0,240.0\n");
        JsonObject completo = s3.json(GOLD + "previsoes.json");

        executar(s3, jdbc, "2026-11-11T14:37:20", "2026-11-11 14:37:00,MF,2,90,90,90,120.0,240.0\n");

        assertEquals(completo, s3.json(GOLD + "previsoes.json"));
    }

    @Test
    void geracaoMaisAntigaNoMesmoMinutoNaoSobrescreveDocumento() throws Exception {
        S3Memoria s3 = new S3Memoria();
        JdbcTemplate jdbc = banco();
        String captura = "2026-11-11 14:37:00,MF,2,40,50,60,120.0,240.0\n";
        executar(s3, jdbc, "2026-11-11T14:37:20", captura);
        JsonObject novo = s3.json(GOLD + "previsoes.json");

        executar(s3, jdbc, "2026-11-11T14:37:10", captura);

        assertEquals(novo, s3.json(GOLD + "previsoes.json"));
    }

    @Test
    void relerAposConflitoPreservaDocumentoGravadoPorOutraExecucao() throws Exception {
        S3Memoria s3 = new S3Memoria();
        JdbcTemplate jdbc = banco();
        executar(s3, jdbc, "2026-11-11T14:37:10", "2026-11-11 14:37:00,MF,2,40,50,60,120.0,240.0\n");
        JsonObject concorrente = s3.json(GOLD + "previsoes.json");
        concorrente.addProperty("atualizado_em", "2026-11-11T14:50-03:00");
        concorrente.addProperty("gerado_em", "2026-11-11T14:50:10-03:00");
        s3.chaveConcorrente = GOLD + "previsoes.json";
        s3.documentoConcorrente = concorrente;

        executar(s3, jdbc, "2026-11-11T14:40:10", "2026-11-11 14:40:00,MF,2,90,90,90,120.0,240.0\n");

        assertEquals(concorrente, s3.json(GOLD + "previsoes.json"));
        assertEquals("2026-11-11T14:50-03:00", s3.json("sptech/03-gold/previsoes/resumo.json").get("atualizado_em").getAsString());
    }

    @ParameterizedTest
    @ValueSource(ints = {409, 412})
    void repetePublicacaoDoResumoQuandoHaConflito(int status) throws Exception {
        S3Memoria s3 = new S3Memoria();
        s3.statusConflito = status;
        s3.conflitosResumo = 1;

        executar(s3, banco(), "2026-11-11T14:37:10", "2026-11-11 14:37:00,MF,2,40,50,60,120.0,240.0\n");

        assertEquals(Integer.valueOf(2), s3.tentativas.get("sptech/03-gold/previsoes/resumo.json"));
        assertEquals(Integer.valueOf(1), s3.tentativas.get(GOLD + "previsoes.json"));
    }

    @Test
    void sinalizaFalhaDepoisDeQuatroConflitosNaMainframe() {
        S3Memoria s3 = new S3Memoria();
        s3.conflitos = 4;

        IOException erro = assertThrows(IOException.class, () -> executar(s3, banco(),
                "2026-11-11T14:37:10", "2026-11-11 14:37:00,MF,2,40,50,60,120.0,240.0\n"));

        assertEquals("O documento da mainframe mudou durante o envio. Reexecute este evento.", erro.getMessage());
        assertEquals(Integer.valueOf(4), s3.tentativas.get(GOLD + "previsoes.json"));
        assertFalse(s3.objetos.containsKey(GOLD + "previsoes.json"));
    }

    @Test
    void sinalizaFalhaDepoisDeQuatroConflitosNoResumo() {
        S3Memoria s3 = new S3Memoria();
        s3.conflitosResumo = 4;

        IOException erro = assertThrows(IOException.class, () -> executar(s3, banco(),
                "2026-11-11T14:37:10", "2026-11-11 14:37:00,MF,2,40,50,60,120.0,240.0\n"));

        assertEquals("O resumo mudou durante o envio. Reexecute este evento.", erro.getMessage());
        assertEquals(Integer.valueOf(4), s3.tentativas.get("sptech/03-gold/previsoes/resumo.json"));
        assertTrue(s3.objetos.containsKey(GOLD + "previsoes.json"));
        assertFalse(s3.objetos.containsKey("sptech/03-gold/previsoes/resumo.json"));
    }

    @Test
    void propagaFalhaDoS3QueNaoSejaConflito() {
        S3Memoria s3 = new S3Memoria();
        s3.statusConflito = 500;
        s3.conflitos = 1;

        S3Exception erro = assertThrows(S3Exception.class, () -> executar(s3, banco(),
                "2026-11-11T14:37:10", "2026-11-11 14:37:00,MF,2,40,50,60,120.0,240.0\n"));

        assertEquals(500, erro.statusCode());
        assertEquals(Integer.valueOf(1), s3.tentativas.get(GOLD + "previsoes.json"));
    }

    @Test
    void processaUmaSemanaComPadraoDiario() throws IOException {
        StringBuilder historico = new StringBuilder(CAB);
        java.time.format.DateTimeFormatter formato = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime inicio = LocalDateTime.of(2026, 11, 4, 14, 37);
        for (int i = 0; i <= 7 * 1440; i++) {
            LocalDateTime hora = inicio.plusMinutes(i);
            double cpu = 45 + 20 * Math.sin(2 * Math.PI * (hora.getHour() * 60 + hora.getMinute()) / 1440.0);
            historico.append(hora.format(formato)).append(",MF,20,").append(cpu).append(",50,60,120.0,240.0\n");
        }
        CalculosPrevisoes calculos = new CalculosPrevisoes();
        calculos.lerHistorico(new ByteArrayInputStream(historico.toString().getBytes(StandardCharsets.UTF_8)), "MF");
        JsonObject resultado = calculos.calcular(0, 30.0, 80.0, calculos.getAtual().plusSeconds(10));
        assertEquals("Perfil diário", resultado.get("modelo").getAsString());
        assertEquals(33, resultado.getAsJsonObject("graficos").getAsJsonArray("previsao").size());
        assertEquals(168, resultado.getAsJsonObject("graficos").getAsJsonArray("mapa").size());
    }

    private JsonArray curva(double... valores) {
        JsonArray pontos = new JsonArray();
        LocalDateTime inicio = LocalDateTime.of(2026, 11, 11, 14, 10);
        for (int i = 0; i < 36; i++) {
            pontos.add(CalculosPrevisoes.ponto(inicio.plusMinutes(i * 10L), valores[Math.min(i, valores.length - 1)]));
        }
        return pontos;
    }

    private void colocarPrevisao(S3Memoria s3, int id, String hostname, JsonArray pontos, Double minimo, Double maximo) {
        JsonObject[] metricas = new JsonObject[5];
        for (int m = 0; m < 5; m++) {
            JsonObject metrica = new JsonObject();
            metrica.addProperty("unidade", m < 3 ? "%" : "MiB/s");
            CalculosPrevisoes.numero(metrica, "limite_minimo", minimo);
            CalculosPrevisoes.numero(metrica, "limite_maximo", maximo);
            JsonObject graficos = new JsonObject();
            graficos.add("previsao", pontos);
            metrica.add("graficos", graficos);
            metricas[m] = metrica;
        }
        LocalDateTime atualizado = LocalDateTime.of(2026, 11, 11, 14, 0);
        JsonObject documento = new JsonPrevisoes(s3.cliente, "teste").criarDocumento(1,
                new Mainframe(id, hostname, List.of()), atualizado, 1, atualizado.plusSeconds(10), metricas);
        s3.colocar("sptech/03-gold/" + hostname + "/previsoes.json", documento.toString().getBytes(StandardCharsets.UTF_8));
    }

    private JsonObject resumir(S3Memoria s3, String... hostnames) throws IOException {
        new JsonPrevisoes(s3.cliente, "teste").publicarResumo("sptech", 1, List.of(hostnames));
        return s3.json("sptech/03-gold/previsoes/resumo.json");
    }

    @Test
    void resumoContaMaquinasDistintasEConcentracaoInterpoladaParaTodasAsMetricas() throws IOException {
        S3Memoria s3 = new S3Memoria();
        colocarPrevisao(s3, 1, "A", curva(50, 90, 90, 50), 30.0, 80.0);
        colocarPrevisao(s3, 2, "B", curva(50, 50, 90, 50), 30.0, 80.0);
        colocarPrevisao(s3, 3, "C", curva(50), 30.0, 80.0);
        JsonObject resumo = resumir(s3, "A", "B", "C", "A");
        for (int m = 0; m < 5; m++) {
            JsonObject metrica = JsonPrevisoes.metrica(resumo, m);
            JsonObject indicadores = metrica.getAsJsonObject("indicadores");
            assertEquals(3, indicadores.get("mainframes_com_previsao").getAsInt());
            assertEquals(2, indicadores.get("mainframes_com_desvio").getAsInt());
            assertEquals("2026-11-11T14:10-03:00", indicadores.get("inicio_previsao").getAsString());
            assertEquals("2026-11-11T20:00-03:00", indicadores.get("fim_previsao").getAsString());
            JsonObject pico = indicadores.getAsJsonObject("maior_concentracao");
            assertEquals(2, pico.get("quantidade").getAsInt());
            assertEquals("2026-11-11T14:27-03:00", pico.get("inicio").getAsString());
            assertEquals("2026-11-11T14:33-03:00", pico.get("fim").getAsString());
            assertEquals(3, metrica.getAsJsonArray("mainframes").size());
            assertEquals("Acima do máximo", metrica.getAsJsonArray("mainframes").get(0).getAsJsonObject().get("situacao").getAsString());
        }
        assertEquals(Set.of("sptech/03-gold/previsoes/resumo.json", "sptech/03-gold/A/previsoes.json",
                "sptech/03-gold/B/previsoes.json", "sptech/03-gold/C/previsoes.json"), s3.leituras);
    }

    @Test
    void limitesIguaisSaoDentroDaFaixaESemDesviosEnviaConcentracaoNula() throws IOException {
        S3Memoria s3 = new S3Memoria();
        colocarPrevisao(s3, 1, "A", curva(30, 80, 30), 30.0, 80.0);
        JsonObject metrica = JsonPrevisoes.metrica(resumir(s3, "A"), 0);
        JsonObject indicadores = metrica.getAsJsonObject("indicadores");
        assertEquals(1, indicadores.get("mainframes_com_previsao").getAsInt());
        assertEquals(0, indicadores.get("mainframes_com_desvio").getAsInt());
        assertTrue(indicadores.get("maior_concentracao").isJsonNull());
        assertEquals("Dentro da faixa", metrica.getAsJsonArray("mainframes").get(0).getAsJsonObject().get("situacao").getAsString());
    }

    @Test
    void desviosAbaixoEAcimaContamUmaMaquinaESemSomarIntervalosQueSoEncostam() throws IOException {
        S3Memoria s3 = new S3Memoria();
        colocarPrevisao(s3, 1, "A", curva(20, 30, 50), 30.0, 80.0);
        colocarPrevisao(s3, 2, "B", curva(80, 80, 90, 50), 30.0, 80.0);
        JsonObject indicadores = JsonPrevisoes.metrica(resumir(s3, "A", "B"), 0).getAsJsonObject("indicadores");
        assertEquals(2, indicadores.get("mainframes_com_desvio").getAsInt());
        assertEquals(1, indicadores.getAsJsonObject("maior_concentracao").get("quantidade").getAsInt());
        assertEquals("2026-11-11T14:10-03:00", indicadores.getAsJsonObject("maior_concentracao").get("inicio").getAsString());
        assertEquals("2026-11-11T14:33-03:00", indicadores.getAsJsonObject("maior_concentracao").get("fim").getAsString());
        colocarPrevisao(s3, 1, "A", curva(20, 90, 50), 30.0, 80.0);
        JsonObject metrica = JsonPrevisoes.metrica(resumir(s3, "A"), 0);
        assertEquals("Abaixo e acima", metrica.getAsJsonArray("mainframes").get(0).getAsJsonObject().get("situacao").getAsString());
        assertEquals(1, metrica.getAsJsonObject("indicadores").get("mainframes_com_desvio").getAsInt());
    }

    @Test
    void excluiSemLimitesSemArquivoEPrevisoesSemCoberturaCompleta() throws IOException {
        S3Memoria s3 = new S3Memoria();
        colocarPrevisao(s3, 1, "SEM_LIMITE", curva(90), null, null);
        colocarPrevisao(s3, 2, "SEM_PREVISAO", new JsonArray(), 30.0, 80.0);
        JsonArray parcial = curva(50);
        parcial.remove(parcial.size() - 1);
        colocarPrevisao(s3, 3, "PARCIAL", parcial, 30.0, 80.0);
        JsonObject metrica = JsonPrevisoes.metrica(resumir(s3, "SEM_LIMITE", "SEM_PREVISAO", "PARCIAL", "AUSENTE"), 0);
        assertEquals(0, metrica.getAsJsonObject("indicadores").get("mainframes_com_previsao").getAsInt());
        assertEquals(0, metrica.getAsJsonObject("indicadores").get("mainframes_com_desvio").getAsInt());
        for (JsonElement linha : metrica.getAsJsonArray("mainframes")) assertTrue(linha.getAsJsonObject().get("situacao").isJsonNull());
        assertTrue(metrica.getAsJsonArray("mainframes").get(2).getAsJsonObject().get("menor_previsto").isJsonNull());
    }

    @Test
    void empateEscolhePrimeiroIntervaloEJanelaNaoMudaAoTrocarMetrica() throws IOException {
        S3Memoria s3 = new S3Memoria();
        colocarPrevisao(s3, 1, "A", curva(90, 50, 50, 90, 50), 30.0, 80.0);
        JsonObject resumo = resumir(s3, "A");
        JsonObject indicadores = JsonPrevisoes.metrica(resumo, 0).getAsJsonObject("indicadores");
        assertEquals("2026-11-11T14:10-03:00", indicadores.getAsJsonObject("maior_concentracao").get("inicio").getAsString());
        assertEquals("2026-11-11T14:13-03:00", indicadores.getAsJsonObject("maior_concentracao").get("fim").getAsString());
        assertEquals(indicadores, JsonPrevisoes.metrica(resumo, 4).getAsJsonObject("indicadores"));
    }

    @Test
    void resumoSemDocumentosLimpaResultadosAnteriores() throws IOException {
        S3Memoria s3 = new S3Memoria();
        colocarPrevisao(s3, 1, "A", curva(90), 30.0, 80.0);
        resumir(s3, "A");
        JsonObject metrica = JsonPrevisoes.metrica(resumir(s3), 0);
        assertEquals(0, metrica.getAsJsonArray("mainframes").size());
        assertEquals(0, metrica.getAsJsonObject("indicadores").get("mainframes_com_previsao").getAsInt());
        assertTrue(metrica.getAsJsonObject("indicadores").get("maior_concentracao").isJsonNull());
    }
}
