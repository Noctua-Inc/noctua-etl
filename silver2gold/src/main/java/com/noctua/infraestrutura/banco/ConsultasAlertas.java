package com.noctua.infraestrutura.banco;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

public class ConsultasAlertas {
    private final JdbcTemplate jdbc;

    public ConsultasAlertas(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MainframeAlertas> listar(String empresa) {
        return jdbc.query("""
                SELECT m.hostname, COALESCE(NULLIF(l.nome, ''), NULLIF(l.cidade, ''), 'Sem localização') AS localizacao
                FROM mainframe m
                JOIN empresa e ON e.id_empresa = m.fk_empresa
                LEFT JOIN localizacao l ON l.id_localizacao = m.fk_localizacao
                WHERE REPLACE(LOWER(e.razao_social), ' ', '_') = ?
                ORDER BY m.hostname
                """, (rs, linha) -> new MainframeAlertas(rs.getString("hostname"), rs.getString("localizacao")), empresa);
    }

    public record MainframeAlertas(String hostname, String localizacao) {}
}
