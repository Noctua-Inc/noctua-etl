package com.noctua.conection.jdbc;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.commons.dbcp2.BasicDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;

public class DBConnectionProvider {

    private BasicDataSource banco;
    private JdbcTemplate jdbc;

    public DBConnectionProvider() {
        Dotenv dotenv = Dotenv.load();

        String url = "jdbc:mysql://"
                + dotenv.get("DB_HOST") + ":"
                + dotenv.get("DB_PORT") + "/"
                + dotenv.get("DB_NAME");

        banco = new BasicDataSource();
        banco.setUrl(url);
        banco.setUsername(dotenv.get("DB_USER"));
        banco.setPassword(dotenv.get("DB_PASSWORD"));

        jdbc = new JdbcTemplate(banco);
    }

    public JdbcTemplate getJdbcTemplate() {
        return jdbc;
    }

    public void fechar() throws SQLException {
        banco.close();
    }
}