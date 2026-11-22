package com.noctua.processamento.correlacao;

import com.noctua.processamento.historico.LeitorCsv.Historico;
import com.noctua.infraestrutura.banco.ConsultasCorrelacao;
import com.noctua.infraestrutura.banco.ConsultasCorrelacao.Equipamento;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

public class Correlacao {
    private static final DateTimeFormatter DATA_SILVER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public void executar(S3Client s3, JdbcTemplate jdbc, String bucket, Historico historico) throws IOException {
        var consultas = new ConsultasCorrelacao(jdbc);
        var equipamentos = consultas.listarEquipamentos(historico.empresa(), historico.hostname());
        if (equipamentos.isEmpty()) return;
        publicar(s3, bucket, historico, equipamentos.getFirst());
    }

    private void publicar(S3Client s3, String bucket, Historico historico, Equipamento equipamento) throws IOException {
        String destino = historico.empresa() + "/03-gold/" + historico.hostname() + "/correlacao/resumo.json";
        var dados = RequestBody.fromString(Ouro.gerar(historico.hostname(), carregarEquipamento(historico, equipamento)).toString());
        for (int tentativa = 0; tentativa < 4; tentativa++) {
            String etag = etag(s3, bucket, destino);
            // Uma coleta mais nova deve publicar a sua própria versão do resumo.
            if (!Objects.equals(historico.etag(), etag(s3, bucket, historico.chave()))) return;
            var pedido = PutObjectRequest.builder().bucket(bucket).key(destino)
                    .contentType("application/json; charset=utf-8");
            if (etag == null) pedido.overrideConfiguration(c -> c.putHeader("If-None-Match", "*"));
            else pedido.overrideConfiguration(c -> c.putHeader("If-Match", etag));
            try {
                s3.putObject(pedido.build(), dados);
                return;
            } catch (S3Exception erro) {
                if (erro.statusCode() != 409 && erro.statusCode() != 412) throw erro;
            }
        }
        throw new IOException("O resumo de correlação mudou durante a gravação. Reexecute este evento.");
    }

    private List<Ouro.Linha> carregarEquipamento(Historico historico, Equipamento equipamento) {
        if (!equipamento.cpu() && !equipamento.ram() && !equipamento.disco()) return List.of();
        return historico.capturas().stream().map(captura -> new Ouro.Linha(
                    LocalDateTime.parse(captura[0], DATA_SILVER),
                    equipamento.cpu() ? Double.parseDouble(captura[3]) : Double.NaN,
                    equipamento.ram() ? Double.parseDouble(captura[4]) : Double.NaN,
                    equipamento.disco() ? Double.parseDouble(captura[5]) : Double.NaN)).toList();
    }

    private String etag(S3Client s3, String bucket, String chave) {
        try {
            return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(chave).build()).eTag();
        } catch (S3Exception erro) {
            if (erro.statusCode() == 404) return null;
            throw erro;
        }
    }
}
