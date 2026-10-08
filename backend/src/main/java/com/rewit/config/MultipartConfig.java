package com.rewit.config;

import com.rewit.domain.model.ReviewMedia;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Limites do multipart derivados do limite de negócio do upload de mídia (C4), {@link ReviewMedia#MAX_FILE_SIZE_BYTES}
 * (10 MB, o mesmo de {@code chk_review_media_size}). Sem esta configuração valia o padrão do Spring (1 MB por arquivo),
 * que recusava fotos comuns antes de chegarem ao controller.
 *
 * <p>O multipart aceita 1 MB além do limite, para que o excesso chegue ao controller e responda o código de negócio
 * ({@code 413 MEDIA_SIZE_EXCEEDED}); acima disso o servidor recusa com {@code 413 PAYLOAD_TOO_LARGE}. Este bean
 * substitui o {@code MultipartConfigElement} da autoconfiguração.
 */
@Configuration
public class MultipartConfig {

    static final DataSize TOLERANCE = DataSize.ofMegabytes(1);

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        DataSize maxFile = DataSize.ofBytes(ReviewMedia.MAX_FILE_SIZE_BYTES + TOLERANCE.toBytes());
        factory.setMaxFileSize(maxFile);
        // Campos e cabeçalhos do formulário além do arquivo
        factory.setMaxRequestSize(DataSize.ofBytes(maxFile.toBytes() + TOLERANCE.toBytes()));
        return factory.createMultipartConfig();
    }
}
