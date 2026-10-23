import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_commercial_entities.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_commercial_repository.dart';
import 'package:rewit_mobile/features/place/presentation/state/place_business_profile_notifier.dart';

class _FakeCommercialRepository implements PlaceCommercialRepository {
  PlaceDetail? profileToReturn;
  Exception? exceptionToThrow;

  String? lastLoadedPlaceId;
  String? lastUpdatedPlaceId;
  String? lastUpdatedBusinessAccountId;
  String? lastUpdatedDescription;
  String? lastUpdatedPhone;
  String? lastUpdatedWebsiteUrl;
  String? lastUpdatedInstagramUrl;
  String? lastUpdatedFacebookUrl;

  @override
  Future<PlaceDetail> getBusinessProfile(String placeId) async {
    lastLoadedPlaceId = placeId;
    if (exceptionToThrow != null) throw exceptionToThrow!;
    return profileToReturn ??
        PlaceDetail(
          id: placeId,
          name: 'Restaurante Teste',
          slug: 'restaurante-teste',
          category: 'ALIMENTACAO',
          description: 'Descrição inicial',
          addressText: 'Rua Principal, 100',
          validationRadiusMeters: 50,
          origin: 'CLAIMED',
          isVerified: true,
          status: 'ACTIVE',
          phone: '+55 (11) 98765-4321',
          websiteUrl: 'https://teste.com.br',
          instagramUrl: 'https://instagram.com/teste',
          facebookUrl: 'https://facebook.com/teste',
        );
  }

  @override
  Future<PlaceDetail> updateBusinessProfile({
    required String placeId,
    required String businessAccountId,
    String? description,
    String? phone,
    String? websiteUrl,
    String? instagramUrl,
    String? facebookUrl,
  }) async {
    lastUpdatedPlaceId = placeId;
    lastUpdatedBusinessAccountId = businessAccountId;
    lastUpdatedDescription = description;
    lastUpdatedPhone = phone;
    lastUpdatedWebsiteUrl = websiteUrl;
    lastUpdatedInstagramUrl = instagramUrl;
    lastUpdatedFacebookUrl = facebookUrl;

    if (exceptionToThrow != null) throw exceptionToThrow!;

    return PlaceDetail(
      id: placeId,
      name: 'Restaurante Teste',
      slug: 'restaurante-teste',
      category: 'ALIMENTACAO',
      description: description,
      addressText: 'Rua Principal, 100',
      validationRadiusMeters: 50,
      origin: 'CLAIMED',
      isVerified: true,
      status: 'ACTIVE',
      phone: phone,
      websiteUrl: websiteUrl,
      instagramUrl: instagramUrl,
      facebookUrl: facebookUrl,
    );
  }

  @override
  Future<PlaceOpeningHours> getOpeningHours(String placeId) async => throw UnimplementedError();

  @override
  Future<PlaceOpeningHours> updateOpeningHours({
    required String placeId,
    required String businessAccountId,
    required List<DaySchedule> days,
  }) async =>
      throw UnimplementedError();

  @override
  Future<List<PlaceAmenityItem>> getAmenityOptions() async => throw UnimplementedError();

  @override
  Future<PlaceAmenities> getAmenities(String placeId) async => throw UnimplementedError();

  @override
  Future<PlaceAmenities> updateAmenities({
    required String placeId,
    required String businessAccountId,
    required List<String> amenityCodes,
  }) async =>
      throw UnimplementedError();

  @override
  Future<PlaceOffersPage> getOffers(String placeId, {int page = 0, int size = 50}) async =>
      throw UnimplementedError();

  @override
  Future<PlaceProductOffer> upsertOffer({
    required String placeId,
    required String productId,
    required String businessAccountId,
    required String availability,
    double? price,
    String currency = 'BRL',
  }) async =>
      throw UnimplementedError();

  @override
  Future<void> deleteOffer({
    required String placeId,
    required String productId,
    required String businessAccountId,
  }) async =>
      throw UnimplementedError();
}

void main() {
  group('PlaceBusinessProfileNotifier - Validações Estritas de Entrada', () {
    test('validateDescription aceita até 2000 caracteres e recusa acima', () {
      expect(PlaceBusinessProfileNotifier.validateDescription(null), isNull);
      expect(PlaceBusinessProfileNotifier.validateDescription(''), isNull);
      expect(PlaceBusinessProfileNotifier.validateDescription('A' * 2000), isNull);

      final error = PlaceBusinessProfileNotifier.validateDescription('A' * 2001);
      expect(error, isNotNull);
      expect(error, contains('máximo 2000 caracteres'));
    });

    test('validatePhone aceita formatos válidos com 8 a 15 dígitos e pontuação permitida', () {
      expect(PlaceBusinessProfileNotifier.validatePhone(null), isNull);
      expect(PlaceBusinessProfileNotifier.validatePhone(''), isNull);
      expect(PlaceBusinessProfileNotifier.validatePhone('   '), isNull);

      expect(PlaceBusinessProfileNotifier.validatePhone('+55 (11) 98765-4321'), isNull);
      expect(PlaceBusinessProfileNotifier.validatePhone('(11) 98765-4321'), isNull);
      expect(PlaceBusinessProfileNotifier.validatePhone('11987654321'), isNull);
      expect(PlaceBusinessProfileNotifier.validatePhone('1234-5678'), isNull); // 8 dígitos
      expect(PlaceBusinessProfileNotifier.validatePhone('+1 555 123 4567'), isNull);
      expect(PlaceBusinessProfileNotifier.validatePhone('+55.11.98765.4321'), isNull);
    });

    test('validatePhone recusa menos de 8 dígitos, mais de 15 dígitos ou caracteres proibidos', () {
      expect(PlaceBusinessProfileNotifier.validatePhone('1234567'), isNotNull); // 7 dígitos
      expect(PlaceBusinessProfileNotifier.validatePhone('1234567890123456'), isNotNull); // 16 dígitos
      expect(PlaceBusinessProfileNotifier.validatePhone('11-99999-9999a'), isNotNull); // letra
      expect(PlaceBusinessProfileNotifier.validatePhone('++5511999999999'), isNotNull); // duplo +
      expect(PlaceBusinessProfileNotifier.validatePhone('+55 (11) 98765@4321'), isNotNull); // caractere @
      expect(PlaceBusinessProfileNotifier.validatePhone('+${'1' * 33}'), isNotNull); // > 32 caracteres
    });

    test('validateUrl aceita links http e https válidos com host', () {
      expect(PlaceBusinessProfileNotifier.validateUrl(null, 'do site'), isNull);
      expect(PlaceBusinessProfileNotifier.validateUrl('', 'do site'), isNull);
      expect(PlaceBusinessProfileNotifier.validateUrl('   ', 'do site'), isNull);

      expect(PlaceBusinessProfileNotifier.validateUrl('https://meusite.com.br', 'do site'), isNull);
      expect(PlaceBusinessProfileNotifier.validateUrl('http://sub.dominio.org/path?q=1', 'do site'), isNull);
      expect(PlaceBusinessProfileNotifier.validateUrl('https://instagram.com/perfil_oficial', 'do Instagram'), isNull);
      expect(PlaceBusinessProfileNotifier.validateUrl('https://facebook.com/minhapagina', 'do Facebook'), isNull);
    });

    test('validateUrl recusa links inválidos, esquemas não-http, sem host, com espaços ou credenciais', () {
      expect(PlaceBusinessProfileNotifier.validateUrl('ftp://meusite.com', 'do site'), isNotNull);
      expect(PlaceBusinessProfileNotifier.validateUrl('javascript:alert(1)', 'do site'), isNotNull);
      expect(PlaceBusinessProfileNotifier.validateUrl('https://', 'do site'), isNotNull);
      expect(PlaceBusinessProfileNotifier.validateUrl('https://meu site.com', 'do site'), isNotNull);
      expect(PlaceBusinessProfileNotifier.validateUrl('https://user:pass@host.com', 'do site'), isNotNull);
      expect(PlaceBusinessProfileNotifier.validateUrl('https://exemplo.com/${'a' * 501}', 'do site'), isNotNull);
    });
  });

  group('PlaceBusinessProfileNotifier - Fluxos de Estado e Repositório', () {
    late _FakeCommercialRepository repository;
    late PlaceBusinessProfileNotifier notifier;

    setUp(() {
      repository = _FakeCommercialRepository();
      notifier = PlaceBusinessProfileNotifier(repository: repository);
    });

    tearDown(() {
      notifier.dispose();
    });

    test('loadProfile carrega os dados com sucesso e atualiza estado', () async {
      await notifier.loadProfile('place-123');

      expect(repository.lastLoadedPlaceId, 'place-123');
      expect(notifier.profile, isNotNull);
      expect(notifier.profile!.id, 'place-123');
      expect(notifier.profile!.name, 'Restaurante Teste');
      expect(notifier.isLoading, isFalse);
      expect(notifier.errorMessage, isNull);
    });

    test('loadProfile propaga mensagem de erro do ApiException', () async {
      repository.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Not Found',
          status: 404,
          code: 'PLACE_NOT_FOUND',
          detail: 'Local não encontrado na base de dados.',
        ),
      );

      await notifier.loadProfile('place-invalid');

      expect(notifier.profile, isNull);
      expect(notifier.isLoading, isFalse);
      expect(notifier.errorMessage, 'Local não encontrado na base de dados.');
    });

    test('saveProfile falha na validação de cliente antes de chamar repositório', () async {
      final success = await notifier.saveProfile(
        placeId: 'place-123',
        businessAccountId: 'acc-1',
        description: 'Descrição válida',
        phone: '123', // telefone com poucos dígitos
      );

      expect(success, isFalse);
      expect(repository.lastUpdatedPlaceId, isNull);
      expect(notifier.errorMessage, contains('Telefone inválido'));
      expect(notifier.isSaving, isFalse);
    });

    test('saveProfile persiste campos com sucesso e atualiza estado do perfil', () async {
      final success = await notifier.saveProfile(
        placeId: 'place-123',
        businessAccountId: 'acc-1',
        description: 'Nova descrição atualizada',
        phone: '+55 (11) 91234-5678',
        websiteUrl: 'https://novosite.com.br',
        instagramUrl: 'https://instagram.com/novoperfil',
        facebookUrl: 'https://facebook.com/novafb',
      );

      expect(success, isTrue);
      expect(repository.lastUpdatedPlaceId, 'place-123');
      expect(repository.lastUpdatedBusinessAccountId, 'acc-1');
      expect(repository.lastUpdatedDescription, 'Nova descrição atualizada');
      expect(repository.lastUpdatedPhone, '+55 (11) 91234-5678');
      expect(repository.lastUpdatedWebsiteUrl, 'https://novosite.com.br');
      expect(notifier.profile!.description, 'Nova descrição atualizada');
      expect(notifier.successMessage, 'Perfil comercial atualizado com sucesso.');
      expect(notifier.isSaving, isFalse);
    });

    test('saveProfile mapeia erros HTTP 403, 401 e 400 com mensagens amigáveis', () async {
      repository.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Forbidden',
          status: 403,
          code: 'FORBIDDEN',
          detail: 'Acesso negado',
        ),
      );

      final success = await notifier.saveProfile(
        placeId: 'place-123',
        businessAccountId: 'acc-1',
        description: 'Descrição',
      );

      expect(success, isFalse);
      expect(notifier.errorMessage, contains('permissão para gerenciar'));
    });
  });
}
