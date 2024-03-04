package com.github.hfurlan.rinha;

import java.util.HashMap;

public class JSonUtil {

    public static Transacao parseRequest(byte[] request) {
        var params = new HashMap<String, String>();
        outer:
        for (int i = 0; i < request.length; i++) {
            if ((char)request[i] == '"') {
                // comecou um atributo. ir acumulando ate chegar no proximo '"'
                var chave = new StringBuilder();
                for (int j = i + 1; j < request.length; j++) {
                    if ((char)request[j] == '"') {
                        String chaveStr = chave.toString();
                        for (int k = j + 1; k < request.length; k++) {
                            if ((char)request[k] == ':') {
                                var valor = new StringBuilder();
                                for (int w = k + 1; w < request.length; w++) {
                                    if ((char)request[w] == ',' || (char)request[w] == '}') {
                                        params.put(chaveStr, valor.toString());
                                        i = w + 1;
                                        continue outer;
                                    } else {
                                        if (chaveStr.equals("tipo") || chaveStr.equals("descricao")) {
                                            for (int y = w + 1; y < request.length; y++) {
                                                if ((char) request[y] == '"') {
                                                    for (int z = y + 1; z < request.length; z++) {
                                                        if ((char) request[z] == '"') {
                                                            params.put(chaveStr, valor.toString());
                                                            i = z + 1;
                                                            continue outer;
                                                        } else {
                                                            valor.append((char)request[z]);
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            if (request[w] >= 48 && request[w] <= 57) {
                                                valor.append((char)request[w]);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // achou toda a chave, agora pegar o valor
                    } else {
                        chave.append((char)request[j]);
                    }
                }
            }
        }

        try {
            int valor = Integer.parseInt(params.get("valor"));
            char tipo = params.get("tipo").charAt(0);
            String descricao = params.get("descricao");
            return new Transacao(valor, tipo, descricao);
        }catch (Exception e) {
            throw new IllegalArgumentException("valor invalido");
        }
    }
}
