import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_commercial_entities.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_commercial_repository.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_business_profile_edit_screen.dart';

class _FakeCommercialRepository implements PlaceCommercialRepository {
  PlaceDetail? profileToReturn;
  Exception? loadException;
  Exception? saveException;

  String? lastLoadedPlaceId;
  String? lastSavedDescription;
  String? lastSavedPhone;
  String? lastSavedWebsite;
  String? lastSavedInstagram;
  String? lastSavedFacebook;

  @override
  Future<PlaceDetail> getBusinessProfile(String placeId) async {
    lastLoadedPlaceId = placeId;
    if (loadException != null) throw loadException!;
    return profileToReturn ??
        PlaceDetail(
          id: placeId,
          name: 'Bar do Zé',
          slug: 'bar-do-ze',
          category: 'BARES',
          description: 'O melhor chopp artesanal e petiscos tradicionais da região.',
          addressText: 'Rua Augusta, 500',
          validationRadiusMeters: 50,
          origin: 'CLAIMED',
          isVerified: true,
          status: 'ACTIVE',
          phone: '+55 (11) 98765-4321',
          websiteUrl: 'https://bardozesp.com.br',
          instagramUrl: 'https://instagram.com/bardoze',
          facebookUrl: 'https://facebook.com/bardozesp',
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
    lastSavedDescription = description;
    lastSavedPhone = phone;
    lastSavedWebsite = websiteUrl;
    lastSavedInstagram = instagramUrl;
    lastSavedFacebook = facebookUrl;

    if (saveException != null) throw saveException!;

    return PlaceDetail(
      id: placeId,
      name: 'Bar do Zé',
      slug: 'bar-do-ze',
      category: 'BARES',
      description: description,
      addressText: 'Rua Augusta, 500',
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
  group('PlaceBusinessProfileEditScreen Widget Tests (F2.4)', () {
    late _FakeCommercialRepository repository;

    setUp(() {
      repository = _FakeCommercialRepository();
    });

    Widget createWidget({
      required PlaceBusinessProfileEditArgs args,
    }) {
      return MaterialApp(
        home: PlaceBusinessProfileEditScreen(
          args: args,
          commercialRepository: repository,
        ),
      );
    }

    testWidgets('exibe tela de acesso restrito quando isApproved é falso', (tester) async {
      const args = PlaceBusinessProfileEditArgs(
        placeId: 'place-unapproved',
        businessAccountId: 'acc-1',
        placeName: 'Local Não Aprovado',
        isApproved: false,
      );

      await tester.pumpWidget(createWidget(args: args));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('business_profile_unauthorized_view')), findsOneWidget);
      expect(find.text('Acesso Restrito'), findsOneWidget);
      expect(find.text('Apenas estabelecimentos com reivindicação aprovada podem gerenciar o perfil comercial.'), findsOneWidget);
      expect(repository.lastLoadedPlaceId, isNull);
    });

    testWidgets('carrega e preenche campos com os dados existentes do perfil comercial', (tester) async {
      const args = PlaceBusinessProfileEditArgs(
        placeId: 'place-1',
        businessAccountId: 'acc-1',
        placeName: 'Bar do Zé',
        isApproved: true,
      );

      await tester.pumpWidget(createWidget(args: args));
      await tester.pumpAndSettle();

      expect(repository.lastLoadedPlaceId, 'place-1');
      expect(find.text('Bar do Zé'), findsOneWidget);
      expect(find.text('O melhor chopp artesanal e petiscos tradicionais da região.'), findsOneWidget);
      expect(
        find.descendant(
          of: find.byKey(const Key('business_profile_phone_input')),
          matching: find.text('+55 (11) 98765-4321'),
        ),
        findsOneWidget,
      );
      expect(find.text('https://bardozesp.com.br'), findsOneWidget);
      expect(find.text('https://instagram.com/bardoze'), findsOneWidget);
      expect(find.text('https://facebook.com/bardozesp'), findsOneWidget);

      // Aviso de exibição pública
      expect(find.textContaining('A descrição institucional e os contatos públicos serão exibidos'), findsOneWidget);
    });

    testWidgets('atualiza contador de caracteres da descrição dinamicamente', (tester) async {
      const args = PlaceBusinessProfileEditArgs(
        placeId: 'place-1',
        businessAccountId: 'acc-1',
        placeName: 'Bar do Zé',
        isApproved: true,
      );

      await tester.pumpWidget(createWidget(args: args));
      await tester.pumpAndSettle();

      final descField = find.byKey(const Key('business_profile_description_input'));
      expect(descField, findsOneWidget);

      await tester.enterText(descField, 'Nova descricao 20ch.');
      await tester.pumpAndSettle();

      expect(find.text('20 / 2000'), findsOneWidget);
    });

    testWidgets('submissão com sucesso via botão do AppBar salva alterações e exibe SnackBar', (tester) async {
      const args = PlaceBusinessProfileEditArgs(
        placeId: 'place-1',
        businessAccountId: 'acc-1',
        placeName: 'Bar do Zé',
        isApproved: true,
      );

      await tester.pumpWidget(createWidget(args: args));
      await tester.pumpAndSettle();

      final phoneField = find.byKey(const Key('business_profile_phone_input'));
      await tester.enterText(phoneField, '+55 (11) 99999-8888');

      final saveButton = find.byKey(const Key('save_business_profile_button'));
      await tester.tap(saveButton);
      await tester.pumpAndSettle();

      expect(repository.lastSavedPhone, '+55 (11) 99999-8888');
      expect(find.text('Perfil comercial atualizado com sucesso.'), findsOneWidget);
    });

    testWidgets('limpeza de campo opcional envia string vazia para conversão em null no backend', (tester) async {
      const args = PlaceBusinessProfileEditArgs(
        placeId: 'place-1',
        businessAccountId: 'acc-1',
        placeName: 'Bar do Zé',
        isApproved: true,
      );

      await tester.pumpWidget(createWidget(args: args));
      await tester.pumpAndSettle();

      final websiteField = find.byKey(const Key('business_profile_website_input'));
      await tester.enterText(websiteField, '');

      final bottomSaveButton = find.byKey(const Key('save_business_profile_bottom_button'));
      await tester.ensureVisible(bottomSaveButton);
      await tester.pumpAndSettle();
      await tester.tap(bottomSaveButton);
      await tester.pumpAndSettle();

      expect(repository.lastSavedWebsite, '');
      expect(find.text('Perfil comercial atualizado com sucesso.'), findsOneWidget);
    });

    testWidgets('exibe mensagem amigável quando ocorre erro de validação do backend (400)', (tester) async {
      repository.saveException = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Bad Request',
          status: 400,
          code: 'INVALID_PHONE',
          detail: 'Telefone inválido: número com formato incorreto.',
        ),
      );

      const args = PlaceBusinessProfileEditArgs(
        placeId: 'place-1',
        businessAccountId: 'acc-1',
        placeName: 'Bar do Zé',
        isApproved: true,
      );

      await tester.pumpWidget(createWidget(args: args));
      await tester.pumpAndSettle();

      final saveButton = find.byKey(const Key('save_business_profile_button'));
      await tester.tap(saveButton);
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('business_profile_error_banner')), findsOneWidget);
      expect(
        find.descendant(
          of: find.byKey(const Key('business_profile_error_banner')),
          matching: find.text('Telefone inválido: número com formato incorreto.'),
        ),
        findsOneWidget,
      );
    });

    testWidgets('exibe tela de erro com opção de retry quando carregamento inicial falha', (tester) async {
      repository.loadException = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Internal Server Error',
          status: 500,
          code: 'INTERNAL_ERROR',
          detail: 'Falha temporária de comunicação com o servidor.',
        ),
      );

      const args = PlaceBusinessProfileEditArgs(
        placeId: 'place-fail',
        businessAccountId: 'acc-1',
        placeName: 'Local Falha',
        isApproved: true,
      );

      await tester.pumpWidget(createWidget(args: args));
      await tester.pumpAndSettle();

      expect(find.text('Não foi possível carregar o perfil comercial'), findsOneWidget);
      expect(find.text('Falha temporária de comunicação com o servidor.'), findsOneWidget);

      repository.loadException = null;
      await tester.tap(find.text('Tentar novamente'));
      await tester.pumpAndSettle();

      expect(find.text('O melhor chopp artesanal e petiscos tradicionais da região.'), findsOneWidget);
    });
  });
}
