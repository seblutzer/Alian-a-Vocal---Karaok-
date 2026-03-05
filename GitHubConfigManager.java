package com.karaoke;

import org.json.JSONObject;
import javax.swing.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

public class GitHubConfigManager {

    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_FILE = CONFIG_DIR + File.separator + "github_config.json";
    private static JSONObject config;

    // Valores constantes (não pede ao usuário)
    private static final String GITHUB_API_URL = "https://api.github.com/repos/seblutzer/alianca_vocal_musics_files/contents";
    private static final String GITHUB_REPO = "seblutzer/alianca_vocal_musics_files";
    private static final String GITHUB_REF = "main";
    private static final long SYNC_RATE_LIMIT_DELAY = 2000;
    private static final long UPLOAD_RATE_LIMIT_DELAY = 1000;
    private static final String TOKEN_FILE =
            System.getProperty("user.home") + File.separator + ".karaoke" + File.separator + "github_config.json";


    static {
        initializeConfig();
    }

    /**
     * Inicializa as configurações: carrega se existir, cria se não existir
     */
    private static void initializeConfig() {
        try {
            // Criar diretório se não existir
            File configDirFile = new File(CONFIG_DIR);
            if (!configDirFile.exists()) {
                configDirFile.mkdirs();
            }

            File configFile = new File(CONFIG_FILE);

            if (configFile.exists()) {
                // Arquivo existe: carrega
                loadConfig();
            } else {
                // Arquivo não existe: cria com token do usuário
                createConfigFile();
            }

        } catch (Exception e) {
            System.err.println("Erro ao inicializar configuração GitHub: " + e.getMessage());
            throw new RuntimeException("Falha ao inicializar configurações do GitHub", e);
        }
    }

    /**
     * Carrega as configurações do arquivo JSON existente
     */
    private static void loadConfig() {
        try {
            String content = new String(Files.readAllBytes(Paths.get(CONFIG_FILE)));
            config = new JSONObject(content);
        } catch (Exception e) {
            System.err.println("Erro ao carregar configuração GitHub: " + e.getMessage());
            throw new RuntimeException("Falha ao carregar configurações do GitHub", e);
        }
    }

    /**
     * Cria o arquivo de configuração pedindo o token do usuário via GUI
     */
    private static void createConfigFile() {
        String token = null;

        // Janela de diálogo para pedir o token
        JFrame tempFrame = new JFrame();
        tempFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        tempFrame.setVisible(false);

        while (token == null || token.trim().isEmpty()) {
            token = JOptionPane.showInputDialog(
                    tempFrame,
                    "Arquivo de configuração não encontrado!\n\n" +
                            "Por favor, insira seu token do GitHub:\n" +
                            "(Token gerado em: https://github.com/settings/tokens)",
                    "Configuração Inicial - GitHub Token",
                    JOptionPane.INFORMATION_MESSAGE
            );

            if (token == null) {
                // Usuário clicou em Cancelar
                JOptionPane.showMessageDialog(
                        tempFrame,
                        "Token é obrigatório para continuar!",
                        "Erro",
                        JOptionPane.ERROR_MESSAGE
                );
                System.exit(1);
            }

            if (token.trim().isEmpty()) {
                JOptionPane.showMessageDialog(
                        tempFrame,
                        "O token não pode estar vazio!",
                        "Erro",
                        JOptionPane.ERROR_MESSAGE
                );
                token = null;
            }
        }

        tempFrame.dispose();

        // Cria o JSON com os valores
        config = new JSONObject();
        config.put("github_token", token.trim());
        config.put("github_api_url", GITHUB_API_URL);
        config.put("github_repo", GITHUB_REPO);
        config.put("github_ref", GITHUB_REF);
        config.put("sync_rate_limit_delay", SYNC_RATE_LIMIT_DELAY);
        config.put("upload_rate_limit_delay", UPLOAD_RATE_LIMIT_DELAY);

        // Salva no arquivo
        try {
            String jsonString = config.toString(2); // 2 espaços de indentação
            Files.write(Paths.get(CONFIG_FILE), jsonString.getBytes());
            System.out.println("✓ Arquivo de configuração criado: " + CONFIG_FILE);
        } catch (Exception e) {
            System.err.println("Erro ao salvar configuração: " + e.getMessage());
            throw new RuntimeException("Falha ao salvar configurações do GitHub", e);
        }
    }

    /**
     * Obtém o token do GitHub
     */
    public static String getToken() {
        return getConfigValue("github_token");
    }

    /**
     * Obtém a URL da API do GitHub
     */
    public static String getApiUrl() {
        return GITHUB_API_URL;
    }

    /**
     * Obtém o repositório do GitHub
     */
    public static String getRepo() {
        return GITHUB_REPO;
    }

    /**
     * Obtém o branch padrão
     */
    public static String getRef() {
        return GITHUB_REF;
    }

    /**
     * Obtém o delay de rate limit para sincronização
     */
    public static long getSyncRateLimit() {
        return SYNC_RATE_LIMIT_DELAY;
    }

    /**
     * Obtém o delay de rate limit para upload
     */
    public static long getUploadRateLimit() {
        return UPLOAD_RATE_LIMIT_DELAY;
    }

    /**
     * Método genérico para obter valor string (obrigatório)
     */
    private static String getConfigValue(String key) {
        try {
            if (!config.has(key)) {
                throw new RuntimeException("Configuração obrigatória não encontrada: " + key);
            }
            return config.getString(key);
        } catch (Exception e) {
            throw new RuntimeException("Erro ao ler configuração: " + key, e);
        }
    }

    /**
     * Recarrega as configurações (útil para atualizar em tempo de execução)
     */
    public static void reload() {
        loadConfig();
    }

    /**
     * Verifica se o arquivo de configuração existe
     */
    public static boolean configExists() {
        return new File(CONFIG_FILE).exists();
    }

    /**
     * Obtém o caminho do arquivo de configuração
     */
    public static String getConfigPath() {
        return new File(CONFIG_FILE).getAbsolutePath();
    }
}
