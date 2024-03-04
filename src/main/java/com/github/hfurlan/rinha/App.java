package com.github.hfurlan.rinha;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class App {

    private String dbUsername;
    private String dbPassword;
    private String dbHostname;
    private String dbName;
    private int dbMaxConnections;
    private int httpPort;
    private int httpMaxThreads;
    private Random random = new Random();
    private Map<Integer, Integer> clientesLimites = new ConcurrentHashMap<>();

    private Connection[] conns;

    public App() throws SQLException {
        dbUsername = System.getenv("DB_USERNAME");
        if (dbUsername == null) {
            dbUsername = "admin";
        }
        dbPassword = System.getenv("DB_PASSWORD");
        if (dbPassword == null) {
            dbPassword = "123";
        }
        dbHostname = System.getenv("DB_HOSTNAME");
        if (dbHostname == null) {
            dbHostname = "localhost";
        }
        dbName = System.getenv("DB_NAME");
        if (dbName == null) {
            dbName = "rinha";
        }
        String dbMaxConnectionsStr = System.getenv("DB_MAX_CONNECTIONS");
        if (dbMaxConnectionsStr == null) {
            dbMaxConnectionsStr = "10";
        }
        dbMaxConnections = Integer.parseInt(dbMaxConnectionsStr);
        System.out.println("Conectando no BD...");
        System.out.println("DB_HOSTNAME:" + dbHostname);
        System.out.println("DB_NAME:" + dbName);
        System.out.println("DB_USERNAME:" + dbUsername);
        System.out.println("DB_MAX_CONNECTIONS:" + dbMaxConnections);
        System.out.println("VERSAO:0.0.1-SNAPSHOT");

        String url = "jdbc:postgresql://"+dbHostname+"/"+dbName+"?ssl=false";
        conns = new Connection[dbMaxConnections];
        for (int i = 0; i < conns.length; i++) {
            conns[i] = DriverManager.getConnection(url, dbUsername, dbPassword);
        }
    }

    protected void startServer() throws IOException {
        String httpPortStr = System.getenv("HTTP_PORT");
        if (httpPortStr == null) {
            httpPortStr = "9999";
        }
        httpPort = Integer.parseInt(httpPortStr);

        String httpMaxThreadsStr = System.getenv("HTTP_MAX_THREADS");
        if (httpMaxThreadsStr == null) {
            httpMaxThreadsStr = "10";
        }
        httpMaxThreads = Integer.parseInt(httpMaxThreadsStr);
        ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(httpMaxThreads);

        HttpServer server = HttpServer.create(new InetSocketAddress(httpPort), 0);
        server.createContext("/clientes", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String method = exchange.getRequestMethod();
                String[] uriParts = exchange.getRequestURI().toString().split("[/]");
                try {
                    if("GET".equals(method) && uriParts[3].equals("extrato")) {
                        int id = Integer.parseInt(uriParts[2]);
                        handleExtrato(exchange, id);
                    } else if("POST".equals(method) && uriParts[3].equals("transacoes")) {
                        int id = Integer.parseInt(uriParts[2]);
                        handleTransacoes(exchange, id);
                    } else {
                        responseError(exchange, 404, "Pagina nao encontrada");
                    }
                } catch (Exception e) {
                    responseError(exchange, 500, e.getMessage());
                }
            }
        });
        server.setExecutor(threadPoolExecutor);
        server.start();
        System.out.println("Servidor iniciado e aguardando conexoes na PORTA " + httpPort + " (" + httpMaxThreads + " threads)");
    }

    protected void responseError(HttpExchange exchange, int httpErrorCode, String descricao) throws IOException {
        if (httpErrorCode == 500) {
            System.out.println("HTTP 500 - " + descricao);
        }
        OutputStream outputStream = exchange.getResponseBody();
        exchange.sendResponseHeaders(httpErrorCode, descricao.length());
        outputStream.write(descricao.getBytes());
        outputStream.flush();
        outputStream.close();
    }

    protected void handleTransacoes(HttpExchange exchange, int id) throws IOException, SQLException {
        Integer limite = obterClienteLimiteCache(id);
        if (limite == null) {
            responseError(exchange, 404, "Cliente nao encontrado");
            return;
        }

        byte[] request = exchange.getRequestBody().readAllBytes();
        Transacao transacao = null;
        try {
            transacao = JSonUtil.parseRequest(request);
        } catch (Exception e) {
            responseError(exchange, 422, e.getMessage());
            return;
        }
        if (transacao.valor <= 0) {
            responseError(exchange, 422, "Valor deve ser positivo");
            return;
        }
        if (transacao.tipo != 'c' && transacao.tipo != 'd') {
            responseError(exchange, 422, "Tipo deve ser 'c' ou 'd' (minusculo)");
            return;
        }

        if (transacao.descricao.length() < 1 || transacao.descricao.length() > 10) {
            responseError(exchange, 422, "Descricao deve ter tamanho entre 1 e 10");
            return;
        }

        int saldo = -1;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            if (transacao.tipo == 'c') {
                ps = getConnection().prepareStatement("select creditar(?, ?, ?, ?)");
            } else {
                ps = getConnection().prepareStatement("select debitar(?, ?, ?, ?)");
            }
            ps.setInt(1, id);
            ps.setInt(2, transacao.valor);
            ps.setString(3, "" + transacao.tipo);
            ps.setString(4, transacao.descricao);
            rs = ps.executeQuery();
            if (rs.next()) {
                saldo = rs.getInt(1);
            }
        } catch (Exception e) {
            closeQuiet(rs, ps);
            if (e.getMessage().contains("not-null constraint")) {
                responseError(exchange, 404, "Cliente nao encontrado");
            } else if (e.getMessage().contains("check")) {
                responseError(exchange, 422, "Sem saldo");
            } else {
                responseError(exchange, 500, e.getMessage());
            }
            return;
        } finally {
            closeQuiet(rs, ps);
        }

        var json = new StringBuilder();
        json.append("{\"limite\":");
        json.append(limite);
        json.append(",\"saldo\":");
        json.append(saldo);
        json.append("}");
        OutputStream outputStream = exchange.getResponseBody();
        exchange.sendResponseHeaders(200, json.length());
        outputStream.write(json.toString().getBytes());
        outputStream.flush();
        outputStream.close();
    }

    protected void closeQuiet(ResultSet rs, PreparedStatement ps) {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        try {
            if (ps != null) {
                ps.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    protected void handleExtrato(HttpExchange exchange, int id) throws IOException, SQLException {
        Integer limite = obterClienteLimiteCache(id);
        if (limite == null) {
            responseError(exchange, 404, "Cliente nao encontrado");
            return;
        }

        var json = new StringBuilder("{\"saldo\":{\"total\":");
        var dataExtratoStr = LocalDateTime.now().toString();
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = getConnection().prepareStatement("select * from saldos where cliente_id = ?");
            ps.setInt(1, id);
            rs = ps.executeQuery();
            if (rs.next()) {
                json.append(rs.getInt("saldo"));
                json.append(",\"data_extrato\":\"");
                json.append(dataExtratoStr);
                json.append("\",\"limite\":");
                json.append(limite);
                json.append("},\"ultimas_transacoes\":[");
                for (int i = 0; i < 10; i++) {
                    String transacao0Desccricao = rs.getString("transacao_"+i+"_descricao");
                    if (transacao0Desccricao != null) {
                        if (i > 0 ) {
                            json.append(",");
                        }
                        json.append("{\"valor\":");
                        json.append(rs.getInt("transacao_"+i+"_valor"));
                        json.append(",\"tipo\":\"");
                        json.append(rs.getString("transacao_"+i+"_tipo"));
                        json.append("\",\"descricao\":\"");
                        json.append(transacao0Desccricao);
                        json.append("\",\"realizada_em\":\"");
                        json.append(rs.getString("transacao_"+i+"_data_hora_inclusao"));
                        json.append("\"}");
                    }
                }
                json.append("]}");
                OutputStream outputStream = exchange.getResponseBody();
                exchange.sendResponseHeaders(200, json.length());
                outputStream.write(json.toString().getBytes());
                outputStream.flush();
                outputStream.close();
            } else {
                closeQuiet(rs, ps);
                responseError(exchange, 404, "Cliente nao encontrado");
            }
        } catch (Exception e) {
            closeQuiet(rs, ps);
            responseError(exchange, 500, e.getMessage());
        } finally {
            closeQuiet(rs, ps);
        }
    }

    private Connection getConnection() {
        return conns[random.nextInt(dbMaxConnections)];
    }

    protected Integer obterClienteLimiteCache(int id) throws SQLException {
        Integer limite = clientesLimites.get(id);
        if (limite == null) {
            PreparedStatement ps = null;
            ResultSet rs = null;
            try {
                ps = getConnection().prepareStatement("SELECT limite FROM clientes WHERE cliente_id = ?");
                ps.setInt(1, id);
                rs = ps.executeQuery();
                if (rs.next()) {
                    limite = rs.getInt(1);
                    clientesLimites.put(id, limite);
                } else {
                    return null;
                }
            } finally {
                closeQuiet(rs, ps);
            }
        }
        return limite;
    }


    public static void main(String[] args) throws Exception {
        App app = new App();
        app.startServer();
    }
}
