package com.noctua.model;

import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

public class ConsultasPrevisoes {
    private final JdbcTemplate jdbc;

    public ConsultasPrevisoes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Integer buscarIdEmpresa(String prefixoEmpresa) {
        return jdbc.queryForObject("""
                SELECT id_empresa FROM empresa
                WHERE REPLACE(LOWER(razao_social), ' ', '_') = ?
                """, Integer.class, prefixoEmpresa);
    }

    public Mainframe buscarMainframe(String hostname, int idEmpresa) {
        int idMainframe = buscarIdMainframe(hostname, idEmpresa);
        List<Limite> limites = buscarLimites(idMainframe);
        return new Mainframe(idMainframe, hostname, limites);
    }

    public Integer buscarIdMainframe(String hostname, int idEmpresa) {
        return jdbc.queryForObject("""
                SELECT id_mainframe FROM mainframe
                WHERE hostname = ? AND fk_empresa = ?
                """, Integer.class, hostname, idEmpresa);
    }

    public List<Limite> buscarLimites(int idMainframe) {
        return jdbc.query("""
                SELECT c.tipo, p.pico_min, p.pico_max
                FROM parametro p JOIN componente c ON c.id_componente = p.id_componente
                WHERE p.id_mainframe = ? ORDER BY c.tipo
                """, new BeanPropertyRowMapper<>(Limite.class), idMainframe);
    }

    public List<String> buscarHostnamesResumo(int idEmpresa) {
        return jdbc.queryForList("""
                SELECT hostname FROM mainframe WHERE fk_empresa = ? AND status <> 'inativo' ORDER BY id_mainframe
                """, String.class, idEmpresa);
    }
}
