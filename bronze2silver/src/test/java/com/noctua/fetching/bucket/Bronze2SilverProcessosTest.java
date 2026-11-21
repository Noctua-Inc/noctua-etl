package com.noctua.fetching.bucket;

import com.noctua.model.ProcessoSilver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Bronze2SilverProcessosTest {

    @TempDir
    Path temporario;

    private static final String CABECALHO_BRUTO =
            "capture_id,hostname,timestamp,pid,process_name,cpu_percent,memoria_bytes,memoria_mb,bytes_read,bytes_written";

    @Test
    void converteLinhaBrutaEMantemIoNuloNoUltimoCampo() {
        String linha = "cap1,MF,2026-11-11 19:30:00,123,chrome,12.5,104857600,12.5,1024,";

        ProcessoSilver processo = ProcessoSilver.fromBronzeLine(linha).orElseThrow();

        assertEquals("cap1", processo.captureId());
        assertEquals("MF", processo.hostname());
        assertEquals(123, processo.pid());
        assertEquals("chrome", processo.processName());
        assertEquals(12.5, processo.memoryMb());
        assertEquals(1024L, processo.bytesRead());
        assertNull(processo.bytesWritten());
        assertTrue(processo.toCsvLine().endsWith(","));

        // O mesmo registro deve sobreviver à ida e volta pelo CSV da Silver.
        assertEquals(processo, ProcessoSilver.fromSilverLine(processo.toCsvLine()).orElseThrow());
    }

    @Test
    void rejeitaLinhasIncompletasSemNomeOuComNumeroInvalido() {
        assertTrue(ProcessoSilver.fromBronzeLine("cap1,MF").isEmpty());
        assertTrue(ProcessoSilver.fromBronzeLine("cap1,MF,ts,1,nan,1,2,3,4,5").isEmpty());
        assertTrue(ProcessoSilver.fromBronzeLine("cap1,MF,ts,,chrome,1,2,3,4,5").isEmpty());
        assertTrue(ProcessoSilver.fromBronzeLine("cap1,MF,ts,abc,chrome,1,2,3,4,5").isEmpty());
    }

    @Test
    void gravaQuarentenaSemDescartarAsLinhasValidas() throws Exception {
        Path bronze = Files.createDirectories(temporario.resolve("bronze"));
        Path silver = Files.createDirectories(temporario.resolve("silver"));
        String nome = "MF_processos_2026-11-11_19-30.csv";
        String valida = "cap1,MF,2026-11-11 19:30:00,123,chrome,12.5,104857600,12.5,1024,";
        String invalida = "cap2,MF,2026-11-11 19:31:00,abc,chrome,1,2,3,4,5";

        Files.write(bronze.resolve(nome),
                (CABECALHO_BRUTO + "\n" + valida + "\n" + invalida + "\n").getBytes(StandardCharsets.UTF_8));

        new Bronze2SilverProcessos().transformar(bronze, silver, nome);

        List<String> linhasSilver = Files.readAllLines(silver.resolve("MF_processos_2026-11-11_19-30_silver.csv"));
        assertEquals(2, linhasSilver.size());
        assertEquals(ProcessoSilver.CABECALHO, linhasSilver.get(0));
        assertEquals(chrome().toCsvLine(), linhasSilver.get(1));

        List<String> linhasQuarentena = Files.readAllLines(
                silver.resolve("MF_processos_2026-11-11_19-30_quarentena.csv"));
        assertEquals(1, linhasQuarentena.size());
        assertEquals(invalida, linhasQuarentena.get(0));
    }

    @Test
    void comparaChaveSilver() {
        String nome = "MF_processos_2026-11-11_19-30.csv";
        String chave = new Bronze2SilverProcessos().caminhoSilver(
                "sptech/01-bronze/" + nome, "MF", nome);

        assertEquals("sptech/02-silver/MF/MF_processos_2026-11-11_19-30_silver.csv", chave);
    }

    @Test
    void limpezaNaoApagaOQuarentenaDoProprioLote() {
        String prefixo = "sptech/02-silver/MF/";
        String silver = prefixo + "MF_processos_2026-11-11_19-30_silver.csv";
        String quarentena = prefixo + "MF_processos_2026-11-11_19-30_quarentena.csv";
        Bronze2SilverProcessos etl = new Bronze2SilverProcessos();

        // A quarentena ordena antes da silver: nao pode ser tratada como lote anterior.
        assertFalse(etl.eLoteAnterior(quarentena, prefixo, silver, quarentena));
        assertFalse(etl.eLoteAnterior(silver, prefixo, silver, quarentena));

        // Lote realmente antigo (timestamp menor) deve ser apagado.
        assertTrue(etl.eLoteAnterior(
                prefixo + "MF_processos_2026-11-11_19-28_silver.csv", prefixo, silver, quarentena));
        assertTrue(etl.eLoteAnterior(
                prefixo + "MF_processos_2026-11-11_19-28_quarentena.csv", prefixo, silver, quarentena));

        // Arquivos que nao sao lotes de processos nao podem ser atingidos.
        assertFalse(etl.eLoteAnterior(prefixo + "MF_metricas_2026-11-11_19-28.csv", prefixo, silver, quarentena));
    }

    private ProcessoSilver chrome() {
        return new ProcessoSilver("cap1", "MF", "2026-11-11 19:30:00", 123,
                "chrome", 12.5, 12.5, 1024L, null, null, null);
    }
}
