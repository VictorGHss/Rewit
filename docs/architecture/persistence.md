# Arquitetura de Persistência e Identidade (Step 3)

Este documento descreve a modelagem, padrões e decisões arquiteturais adotados para a camada de persistência das entidades de identidade (`User` e `Profile`) na plataforma **Rewit**.

---

## 1. Separação Estrita entre Domínio e Persistência

Seguindo os princípios da **Arquitetura Limpa (Clean Architecture)** e **Arquitetura Hexagonal (Ports & Adapters)**:
- **Domínio Puro**: O modelo de negócio (`com.rewit.domain.model.User` e `com.rewit.domain.model.Profile`) é agnóstico a frameworks, bancos relacionais e ORMs. Não contém nenhuma anotação JPA (`@Entity`, `@Table`, `@Column`, `@Id`), Spring (`@Component`, `@Repository`) ou Hibernate.
- **Invariantes no Domínio**: Regras como normalização de e-mail (lowercase + trim), validação de formato, normalização de handle (`@` inicial removido, trim, lowercase), pontuação de reputação não-negativa (`reputationScore >= 0`) e soft-delete são encapsuladas exclusivamente nas entidades de domínio.
- **Infraestrutura Isolada**: O mapeamento objeto-relacional ocorre exclusivamente em `com.rewit.infrastructure.persistence.entity` (`UserJpaEntity` e `ProfileJpaEntity`), garantindo que o acoplamento com o PostgreSQL e Hibernate permaneça confinado na fronteira externa do sistema.

```
       +---------------------------------------------+
       |             Camada de Domínio               |
       |  User, Profile, AuthProvider, Regras Puras  |
       +---------------------------------------------+
                              ^
                              | (Implementa Portas)
       +---------------------------------------------+
       |             Camada de Aplicação             |
       |     UserRepository, ProfileRepository       |
       +---------------------------------------------+
                              ^
                              | (Implementação Técnica)
       +---------------------------------------------+
       |          Camada de Infraestrutura           |
       |   UserRepositoryAdapter, UserJpaEntity      |
       |   ProfileRepositoryAdapter, ProfileJpaEntity|
       |   UserJpaRepository, ProfileJpaRepository   |
       +---------------------------------------------+
                              |
                              v
       +---------------------------------------------+
       |        PostgreSQL 18 + PostGIS 3.6          |
       |     Flyway Migrations (V1, V2, V3, V4)      |
       +---------------------------------------------+
```

---

## 2. Portas e Adaptadores (Ports & Adapters)

### Portas de Aplicação (`com.rewit.application.port`)
- [UserRepository](file:///backend/src/main/java/com/rewit/application/port/UserRepository.java):
  - `save(User user)`: Persiste ou atualiza uma entidade de domínio `User`.
  - `findById(UUID id)`: Recupera usuário ativo por seu identificador (`deleted_at IS NULL`).
  - `findByEmail(String email)`: Recupera usuário ativo por e-mail normalizado.
  - `existsByEmail(String email)`: Verifica existência de e-mail reservado (inclusive soft-deleted).
  - `findByAuthProviderAndProviderUserId(AuthProvider, String)`: Localiza usuário ativo via identidade federada.
- [ProfileRepository](file:///backend/src/main/java/com/rewit/application/port/ProfileRepository.java):
  - `save(Profile profile)`: Persiste ou atualiza o perfil público do usuário.
  - `findById(UUID id)`: Recupera perfil por ID do perfil.
  - `findByUserId(UUID userId)`: Recupera perfil vinculado ao usuário especificado.
  - `findByHandle(String handle)`: Recupera perfil por `@handle` normalizado case-insensitive.
  - `existsByHandle(String handle)`: Verifica disponibilidade pública do handle.
  - `existsByUserId(UUID userId)`: Verifica existência de perfil vinculado ao usuário.

### Adaptadores de Infraestrutura (`com.rewit.infrastructure.persistence.adapter`)
- [UserRepositoryAdapter](file:///backend/src/main/java/com/rewit/infrastructure/persistence/adapter/UserRepositoryAdapter.java):
  - Converte entre `User` e `UserJpaEntity`.
  - Executa persistência imediata com validação de constraints via `saveAndFlush`.
  - Nunca expõe entidades JPA para as camadas superiores.
- [ProfileRepositoryAdapter](file:///backend/src/main/java/com/rewit/infrastructure/persistence/adapter/ProfileRepositoryAdapter.java):
  - Garante integridade referencial: valida a existência de `User` no repositório antes de persistir o perfil.
  - Impede a criação de perfis órfãos (`USER_NOT_FOUND`).
  - Converte entre `Profile` e `ProfileJpaEntity`.

---

## 3. Estratégia de Identidade e UUIDs

- **Identificadores Nativos**: Tanto o domínio quanto a persistência utilizam `java.util.UUID`.
- **Compatibilidade PostgreSQL**: A coluna física é do tipo `UUID` (gerado nativamente pelo PostgreSQL via `gen_random_uuid()` ou atribuído pelo domínio).
- **Sem IDs Numéricos**: Nenhuma sequência (`SEQUENCE`) ou autoincremento (`IDENTITY`) numérico é utilizado, prevenindo ataques de enumeração horizontal e facilitando distribuição futura.
- **IDs Externos**: Identificadores de provedores federados (`providerUserId`) nunca são utilizados como chave primária interna; eles são tratados como dados de autenticação secundária.

---

## 4. Estratégia de Integridade e Unicidade de E-mail (Flyway V4)

A migração `V4__identity_integrity.sql` consolidou a integridade de e-mail no banco de dados:
1. **Case-Insensitive Unconditional**:
   ```sql
   CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lower 
       ON users (LOWER(email));
   ```
2. **Decisão de Soft-Delete para o MVP**:
   - O e-mail permanece **estritamente reservado mesmo após soft-delete** (`deleted_at IS NOT NULL`).
   - **Após o purge (C2.3)**: o endereço sai do banco e a coluna passa a guardar `hex(HMAC-SHA256(segredo, e-mail normalizado))@deleted.invalid` (porta `EmailReservation`, implementação `HmacEmailReservation`). O registro procura também esse valor, então a reserva continua; o domínio `deleted.invalid` (RFC 2606) é recusado no cadastro, e nenhum usuário ocupa uma reserva de antemão.
   - **Segredo**: `rewit.account.email-reservation-secret`, variável `ACCOUNT_EMAIL_RESERVATION_SECRET` (mínimo 32 bytes, estável e igual em todas as instâncias). Sem ele o purge não executa (sem fallback para hash sem chave); trocá-lo invalida as reservas já gravadas, que o registro deixa de reconhecer. Sem o segredo, quem tem acesso ao banco não consegue confirmar um e-mail candidato contra as reservas.
   - **Justificativa**: Evita sequestro de identidade (account takeover), colisões com auditorias históricas, e impersonação de contas desativadas no MVP. Caso uma política de liberação ou reciclagem de e-mail seja adotada no futuro, ela será explicitamente versionada via nova migração Flyway e política de expurgo de dados LGPD/GDPR.

---

## 5. Provedores de Autenticação Federada (Auth Provider)

O campo `auth_provider` suporta conceitualmente:
- `LOCAL`: Contas criadas nativamente na plataforma com `password_hash`. `provider_user_id` permanece `NULL`.
- `GOOGLE`: Identidades federadas Google OAuth.
- `APPLE`: Identidades federadas Sign in with Apple.

Para proteger a integridade sem restringir contas locais, o índice na migração V4 é **parcial**:
```sql
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_provider_user_id 
    ON users (auth_provider, provider_user_id) 
    WHERE provider_user_id IS NOT NULL;
```
Isso garante:
- Múltiplos usuários locais (`LOCAL`, `provider_user_id = NULL`) coexistem sem colisão.
- Provedores externos têm garantia absoluta de que um mesmo `provider_user_id` não pode ser associado a dois registros distintos.

---

## 6. Vínculo 1:1 entre User e Profile

- **Chave Estrangeira Única**: A tabela `profiles` possui `CONSTRAINT uq_profiles_user_id UNIQUE (user_id)` e `REFERENCES users(id) ON DELETE CASCADE`.
- **Mapeamento JPA**:
  ```java
  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false, unique = true)
  private UserJpaEntity user;
  ```
- **Prevenção de Órfãos**: `ProfileRepositoryAdapter` valida a existência do usuário antes da inserção e rejeita criações órfãs com `BusinessException("USER_NOT_FOUND")`.
- **Proteção Contra Cascades Acidentais**: Não há cascade de remoção de `Profile` para `User`. A exclusão de um perfil não pode apagar o usuário. O soft-delete do `User` mantém os dados de `Profile` íntegros para preservação do histórico de avaliações da rede social.
