package com.rewit.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.junit.jupiter.api.Assertions.assertFalse;

@DisplayName("Testes Arquiteturais: Preservação de Fronteiras da Clean Architecture em PlaceMediaService (M-4)")
class PlaceMediaArchitectureTest {

    private static final String FORBIDDEN_PERSISTENCE_PKG = "com.rewit.infrastructure.persistence";
    private static final String FORBIDDEN_PRESENTATION_PKG = "com.rewit.presentation";

    @Test
    @DisplayName("PlaceMediaService não deve possuir nenhum construtor acoplado a repositórios ou adaptadores JPA")
    void placeMediaServiceConstructorsMustNotDependOnJpaInfrastructure() {
        for (Constructor<?> ctor : PlaceMediaService.class.getDeclaredConstructors()) {
            for (Class<?> paramType : ctor.getParameterTypes()) {
                String typeName = paramType.getName();
                assertFalse(typeName.startsWith(FORBIDDEN_PERSISTENCE_PKG),
                        "Construtor de PlaceMediaService viola Clean Architecture referenciando persistência JPA: " + typeName);
                assertFalse(typeName.startsWith(FORBIDDEN_PRESENTATION_PKG),
                        "Construtor de PlaceMediaService viola Clean Architecture referenciando presentation: " + typeName);
            }
        }
    }

    @Test
    @DisplayName("PlaceMediaService não deve possuir métodos públicos acoplados a tipos de persistência ou presentation")
    void placeMediaServiceMethodsMustNotDependOnJpaInfrastructure() {
        for (Method method : PlaceMediaService.class.getDeclaredMethods()) {
            String returnTypeName = method.getReturnType().getName();
            assertFalse(returnTypeName.startsWith(FORBIDDEN_PERSISTENCE_PKG),
                    "Método " + method.getName() + " retorna tipo de persistência proibido: " + returnTypeName);
            assertFalse(returnTypeName.startsWith(FORBIDDEN_PRESENTATION_PKG),
                    "Método " + method.getName() + " retorna tipo de presentation proibido: " + returnTypeName);

            for (Parameter param : method.getParameters()) {
                String paramTypeName = param.getType().getName();
                assertFalse(paramTypeName.startsWith(FORBIDDEN_PERSISTENCE_PKG),
                        "Método " + method.getName() + " recebe parâmetro de persistência proibido: " + paramTypeName);
                assertFalse(paramTypeName.startsWith(FORBIDDEN_PRESENTATION_PKG),
                        "Método " + method.getName() + " recebe parâmetro de presentation proibido: " + paramTypeName);
            }
        }
    }

    @Test
    @DisplayName("PlaceMediaService não deve conter campos declarados de infraestrutura de persistência ou presentation")
    void placeMediaServiceFieldsMustNotDependOnJpaInfrastructure() {
        for (Field field : PlaceMediaService.class.getDeclaredFields()) {
            String fieldTypeName = field.getType().getName();
            assertFalse(fieldTypeName.startsWith(FORBIDDEN_PERSISTENCE_PKG),
                    "Campo " + field.getName() + " é do tipo de persistência proibido: " + fieldTypeName);
            assertFalse(fieldTypeName.startsWith(FORBIDDEN_PRESENTATION_PKG),
                    "Campo " + field.getName() + " é do tipo de presentation proibido: " + fieldTypeName);
        }
    }
}

