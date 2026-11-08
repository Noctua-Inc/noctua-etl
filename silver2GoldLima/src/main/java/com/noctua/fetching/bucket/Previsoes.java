package com.noctua.fetching.bucket;

import com.noctua.conection.jdbc.DBConnectionProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import com.noctua.model.Limite;
import org.springframework.jdbc.core.BeanPropertyRowMapper;

import java.util.List;

public class Previsoes {

    public static void main(String[] args) throws Exception {
        DBConnectionProvider conexao = new DBConnectionProvider();

        try {
            JdbcTemplate jdbc = conexao.getJdbcTemplate();

            String hostname = "MF-SPTECH-001";
            int idEmpresa = 1;

            String sqlMainframe =
                """
                SELECT id_mainframe
                FROM mainframe
                WHERE hostname = ?
                    AND fk_empresa = ?
                """;

            Integer idMainframe = jdbc.queryForObject(
                    sqlMainframe,
                    Integer.class,
                    hostname,
                    idEmpresa
            );

            System.out.println("Mainframe encontrada: " + idMainframe);


            String sql =
                """
                SELECT c.tipo, p.pico_min, p.pico_max
                FROM parametro p
                JOIN componente c
                    ON c.id_componente = p.id_componente
                WHERE p.id_mainframe = ?
                ORDER BY c.tipo
                """;

            List<Limite> limites = jdbc.query(
                    sql,
                    new BeanPropertyRowMapper<>(Limite.class),
                    idMainframe
            );

            for (Limite limite : limites) {
                System.out.println("Componente: " + limite.getTipo());
                System.out.println("Mínimo: " + limite.getPicoMin());
                System.out.println("Máximo: " + limite.getPicoMax());
            }

        } finally {
            conexao.fechar();
        }
    }
}
