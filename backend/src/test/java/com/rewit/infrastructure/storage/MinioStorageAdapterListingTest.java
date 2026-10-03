package com.rewit.infrastructure.storage;

import com.rewit.application.dto.storage.StorageReconciliationDtos.StoredObject;
import com.rewit.application.dto.storage.StorageReconciliationDtos.StoredObjectPage;
import com.rewit.common.exception.BusinessException;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Testes Unitários: listagem paginada do MinioStorageAdapter (Step 28.1)")
class MinioStorageAdapterListingTest {

    private static final ZonedDateTime MODIFIED = ZonedDateTime.of(2026, 10, 1, 8, 30, 0, 0, ZoneOffset.UTC);

    private final MinioClient minioClient = mock(MinioClient.class);
    private final MinioStorageAdapter adapter = new MinioStorageAdapter(minioClient, "rewit-local");

    @Test
    @DisplayName("Envia prefixo, start-after e max-keys ao SDK e converte os itens para o contrato")
    void mapsArgumentsAndItems() {
        Iterable<Result<Item>> results = new CountingIterable(item("reviews/a", 10));
        when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(results);

        StoredObjectPage page = adapter.listObjects("reviews/", "reviews/0", 5);

        ArgumentCaptor<ListObjectsArgs> args = ArgumentCaptor.forClass(ListObjectsArgs.class);
        verify(minioClient).listObjects(args.capture());
        assertEquals("rewit-local", args.getValue().bucket());
        assertEquals("reviews/", args.getValue().prefix());
        assertEquals("reviews/0", args.getValue().startAfter());
        assertEquals(5, args.getValue().maxKeys());
        assertTrue(args.getValue().recursive());
        assertEquals(List.of(new StoredObject("reviews/a", 10, MODIFIED.toInstant())), page.objects());
        assertFalse(page.hasMore());
    }

    @Test
    @DisplayName("Página cheia retorna a última chave como marcador e não consome itens além de max-keys")
    void fullPageStopsAtMaxKeys() {
        CountingIterable results = new CountingIterable(item("reviews/a", 1), item("reviews/b", 1), item("reviews/c", 1));
        when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(results);

        StoredObjectPage page = adapter.listObjects("reviews/", null, 2);

        assertEquals(List.of("reviews/a", "reviews/b"), page.objects().stream().map(object -> object.key()).toList());
        assertEquals("reviews/b", page.nextStartAfter());
        assertEquals(2, results.consumed);
    }

    @Test
    @DisplayName("Falha do SDK durante a listagem vira STORAGE_LIST_FAILED, sem resultado parcial")
    void sdkFailureIsTranslated() {
        when(minioClient.listObjects(any(ListObjectsArgs.class)))
                .thenReturn(List.of(new Result<Item>(new IOException("connection reset"))));

        BusinessException ex = assertThrows(BusinessException.class, () -> adapter.listObjects("reviews/", null, 2));
        assertEquals("STORAGE_LIST_FAILED", ex.getErrorCode());
    }

    @Test
    @DisplayName("max-keys não positivo é rejeitado antes de chamar o SDK")
    void rejectsNonPositiveMaxKeys() {
        assertThrows(IllegalArgumentException.class, () -> adapter.listObjects("reviews/", null, 0));
        verifyNoInteractions(minioClient);
    }

    private static Item item(String key, long size) {
        Item item = mock(Item.class);
        when(item.objectName()).thenReturn(key);
        when(item.size()).thenReturn(size);
        when(item.lastModified()).thenReturn(MODIFIED);
        return item;
    }

    /** Simula o Iterable preguiçoso do SDK e registra quantos itens foram efetivamente lidos. */
    private static final class CountingIterable implements Iterable<Result<Item>> {

        private final List<Result<Item>> results = new ArrayList<>();
        private int consumed;

        CountingIterable(Item... items) {
            for (Item item : items) {
                results.add(new Result<>(item));
            }
        }

        @Override
        public Iterator<Result<Item>> iterator() {
            Iterator<Result<Item>> delegate = results.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return delegate.hasNext();
                }

                @Override
                public Result<Item> next() {
                    consumed++;
                    return delegate.next();
                }
            };
        }
    }
}
