package com.noctua.infraestrutura.banco;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

public class ConsultasCorrelacao {
    private final JdbcTemplate jdbc;

    public ConsultasCorrelacao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Equipamento> listarEquipamentos(String empresa, String hostname) {
        return jdbc.query("""
                SELECT m.id_mainframe, m.hostname,
                       MAX(CASE WHEN c.tipo = 'CPU' THEN 1 ELSE 0 END) AS cpu,
                       MAX(CASE WHEN c.tipo = 'RAM' THEN 1 ELSE 0 END) AS ram,
                       MAX(CASE WHEN c.tipo = 'DISCO' THEN 1 ELSE 0 END) AS disco
                FROM mainframe m
                JOIN empresa e ON e.id_empresa = m.fk_empresa
                LEFT JOIN parametro p ON p.id_mainframe = m.id_mainframe
                LEFT JOIN componente c ON c.id_componente = p.id_componente
                WHERE REPLACE(LOWER(e.razao_social), ' ', '_') = ? AND m.hostname = ?
                GROUP BY m.id_mainframe, m.hostname
                ORDER BY m.hostname
                """, (rs, linha) -> new Equipamento(rs.getInt("id_mainframe"), rs.getString("hostname"),
                rs.getBoolean("cpu"), rs.getBoolean("ram"), rs.getBoolean("disco")), empresa, hostname);
    }

    public record Equipamento(int id, String hostname, boolean cpu, boolean ram, boolean disco) {}
}
