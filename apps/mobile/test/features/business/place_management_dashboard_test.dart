import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/features/business/domain/entities/business_entities.dart';
import 'package:rewit_mobile/features/business/domain/repositories/business_repository.dart';
import 'package:rewit_mobile/features/business/presentation/screens/my_business_accounts_screen.dart';
import 'package:rewit_mobile/features/business/presentation/screens/place_management_dashboard_screen.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_media_gallery_screen.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_amenities_edit_screen.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_opening_hours_edit_screen.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_offers_management_screen.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_business_profile_edit_screen.dart';


class _FakeBusinessRepository implements BusinessRepository {
  List<BusinessAccount> accountsToReturn = [];
  Map<String, List<PlaceClaim>> claimsToReturn = {};

  @override
  Future<List<BusinessAccount>> getMyBusinessAccounts() async => accountsToReturn;

  @override
  Future<BusinessAccount> createBusinessAccount({
    required String corporateName,
    required String taxId,
  }) async =>
      throw UnimplementedError();

  @override
  Future<PlaceClaim> requestPlaceClaim({
    required String businessAccountId,
    required String placeId,
    required String evidenceDescription,
  }) async =>
      throw UnimplementedError();

  @override
  Future<List<PlaceClaim>> getBusinessPlaceClaims(
    String businessAccountId, {
    int page = 0,
    int size = 20,
    String? status,
  }) async =>
      claimsToReturn[businessAccountId] ?? [];
}

void main() {
  group('PlaceManagement - Ação de Gestão na Lista de Empresas', () {
    late _FakeBusinessRepository repository;

    setUp(() {
      repository = _FakeBusinessRepository();
      repository.accountsToReturn = [
        BusinessAccount(
          id: 'acc-1',
          corporateName: 'Padaria Central Ltda',
          taxId: '12345678000190',
          verificationStatus: 'APPROVED',
          planTier: 'FREE',
          createdAt: DateTime.now(),
          updatedAt: DateTime.now(),
        ),
      ];
    });

    testWidgets('exibe botão "Gerenciar estabelecimento" apenas para reivindicação APPROVED',
        (tester) async {
      repository.claimsToReturn['acc-1'] = [
        PlaceClaim(
          id: 'claim-approved',
          businessAccountId: 'acc-1',
          corporateName: 'Padaria Central Ltda',
          taxId: '12345678000190',
          placeId: 'place-approved-1',
          placeName: 'Padaria Central Centro',
          city: 'São Paulo',
          state: 'SP',
          status: 'APPROVED',
          evidenceDescription: 'Contrato de locação comercial e alvará de funcionamento.',
          createdAt: DateTime.now(),
          decidedAt: DateTime.now(),
        ),
      ];

      await tester.pumpWidget(
        MaterialApp(
          home: MyBusinessAccountsScreen(repository: repository),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Gerenciar estabelecimento'), findsOneWidget);
      expect(find.byKey(const Key('manage_place_button_place-approved-1')), findsOneWidget);
    });

    testWidgets('não exibe botão de gestão para reivindicações PENDING ou REJECTED',
        (tester) async {
      repository.claimsToReturn['acc-1'] = [
        PlaceClaim(
          id: 'claim-pending',
          businessAccountId: 'acc-1',
          corporateName: 'Padaria Central Ltda',
          taxId: '12345678000190',
          placeId: 'place-pending-1',
          placeName: 'Filial Pendente',
          city: 'São Paulo',
          state: 'SP',
          status: 'PENDING',
          evidenceDescription: 'Comprovante em análise na prefeitura.',
          createdAt: DateTime.now(),
        ),
        PlaceClaim(
          id: 'claim-rejected',
          businessAccountId: 'acc-1',
          corporateName: 'Padaria Central Ltda',
          taxId: '12345678000190',
          placeId: 'place-rejected-2',
          placeName: 'Filial Rejeitada',
          city: 'São Paulo',
          state: 'SP',
          status: 'REJECTED',
          evidenceDescription: 'Documento ilegível enviado.',
          decisionReason: 'Documento com rasuras.',
          createdAt: DateTime.now(),
          decidedAt: DateTime.now(),
        ),
      ];

      await tester.pumpWidget(
        MaterialApp(
          home: MyBusinessAccountsScreen(repository: repository),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Gerenciar estabelecimento'), findsNothing);
      expect(find.byKey(const Key('manage_place_button_place-pending-1')), findsNothing);
      expect(find.byKey(const Key('manage_place_button_place-rejected-2')), findsNothing);
    });

    testWidgets('navega transmitindo placeId e businessAccountId corretos ao tocar em Gerenciar',
        (tester) async {
      repository.claimsToReturn['acc-1'] = [
        PlaceClaim(
          id: 'claim-1',
          businessAccountId: 'acc-1',
          corporateName: 'Padaria Central Ltda',
          taxId: '12345678000190',
          placeId: 'place-xyz-99',
          placeName: 'Padaria Central Jardins',
          city: 'São Paulo',
          state: 'SP',
          status: 'APPROVED',
          evidenceDescription: 'Alvará válido.',
          createdAt: DateTime.now(),
        ),
      ];

      PlaceManagementArgs? capturedArgs;

      await tester.pumpWidget(
        MaterialApp(
          onGenerateRoute: (settings) {
            if (settings.name == AppRouter.placeManagement) {
              capturedArgs = settings.arguments as PlaceManagementArgs?;
              return MaterialPageRoute(
                builder: (_) => const Scaffold(body: Text('PlaceManagementScreen Mock')),
              );
            }
            return MaterialPageRoute(
              builder: (_) => MyBusinessAccountsScreen(repository: repository),
            );
          },
        ),
      );
      await tester.pumpAndSettle();

      final manageBtn = find.byKey(const Key('manage_place_button_place-xyz-99'));
      expect(manageBtn, findsOneWidget);

      await tester.tap(manageBtn);
      await tester.pumpAndSettle();

      expect(capturedArgs, isNotNull);
      expect(capturedArgs!.placeId, 'place-xyz-99');
      expect(capturedArgs!.businessAccountId, 'acc-1');
      expect(capturedArgs!.placeName, 'Padaria Central Jardins');
      expect(capturedArgs!.city, 'São Paulo');
      expect(capturedArgs!.state, 'SP');
      expect(capturedArgs!.status, 'APPROVED');
    });
  });

  group('PlaceManagementDashboardScreen', () {
    testWidgets('exibe contexto e opções funcionais quando reivindicação é APPROVED',
        (tester) async {
      const args = PlaceManagementArgs(
        placeId: 'place-123',
        businessAccountId: 'acc-456',
        placeName: 'Café das Flores',
        city: 'Curitiba',
        state: 'PR',
        status: 'APPROVED',
      );

      await tester.pumpWidget(
        const MaterialApp(
          home: PlaceManagementDashboardScreen(args: args),
        ),
      );

      expect(find.text('Gestão do Estabelecimento'), findsOneWidget);
      expect(find.text('Café das Flores'), findsOneWidget);
      expect(find.text('Curitiba / PR'), findsOneWidget);
      expect(find.text('Reivindicação Aprovada'), findsOneWidget);
      expect(find.byKey(const Key('manage_media_option')), findsOneWidget);
      expect(find.byKey(const Key('view_public_profile_option')), findsOneWidget);
    });

    testWidgets('bloqueia acesso se status da reivindicação não for APPROVED',
        (tester) async {
      const args = PlaceManagementArgs(
        placeId: 'place-123',
        businessAccountId: 'acc-456',
        placeName: 'Café das Flores',
        city: 'Curitiba',
        state: 'PR',
        status: 'PENDING',
      );

      await tester.pumpWidget(
        const MaterialApp(
          home: PlaceManagementDashboardScreen(args: args),
        ),
      );

      expect(find.text('Acesso Restrito'), findsOneWidget);
      expect(find.text('Apenas estabelecimentos com reivindicação aprovada podem ser gerenciados.'), findsOneWidget);
      expect(find.byKey(const Key('manage_media_option')), findsNothing);
    });

    testWidgets('toque em "Fotografias e capa" navega para a tela de galeria com argumentos corretos',
        (tester) async {
      const args = PlaceManagementArgs(
        placeId: 'place-abc',
        businessAccountId: 'acc-def',
        placeName: 'Livraria Central',
        city: 'Porto Alegre',
        state: 'RS',
        status: 'APPROVED',
      );

      PlaceMediaGalleryArgs? capturedGalleryArgs;

      await tester.pumpWidget(
        MaterialApp(
          onGenerateRoute: (settings) {
            if (settings.name == AppRouter.placeMediaGallery) {
              capturedGalleryArgs = settings.arguments as PlaceMediaGalleryArgs?;
              return MaterialPageRoute(
                builder: (_) => const Scaffold(body: Text('Gallery Screen Mock')),
              );
            }
            return MaterialPageRoute(
              builder: (_) => const PlaceManagementDashboardScreen(args: args),
            );
          },
        ),
      );

      await tester.tap(find.byKey(const Key('manage_media_option')));
      await tester.pumpAndSettle();

      expect(capturedGalleryArgs, isNotNull);
      expect(capturedGalleryArgs!.placeId, 'place-abc');
      expect(capturedGalleryArgs!.businessAccountId, 'acc-def');
      expect(capturedGalleryArgs!.placeName, 'Livraria Central');
    });

    testWidgets('toque em "Perfil comercial" navega para a tela de edição do perfil comercial com argumentos corretos',
        (tester) async {
      const args = PlaceManagementArgs(
        placeId: 'place-prof-123',
        businessAccountId: 'acc-prof-456',
        placeName: 'Empório Gourmet',
        city: 'Curitiba',
        state: 'PR',
        status: 'APPROVED',
      );

      PlaceBusinessProfileEditArgs? capturedArgs;

      await tester.pumpWidget(
        MaterialApp(
          onGenerateRoute: (settings) {
            if (settings.name == AppRouter.placeBusinessProfileEdit) {
              capturedArgs = settings.arguments as PlaceBusinessProfileEditArgs?;
              return MaterialPageRoute(
                builder: (_) => const Scaffold(body: Text('Business Profile Edit Mock')),
              );
            }
            return MaterialPageRoute(
              builder: (_) => const PlaceManagementDashboardScreen(args: args),
            );
          },
        ),
      );

      await tester.ensureVisible(find.byKey(const Key('manage_business_profile_option')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('manage_business_profile_option')));
      await tester.pumpAndSettle();

      expect(capturedArgs, isNotNull);
      expect(capturedArgs!.placeId, 'place-prof-123');
      expect(capturedArgs!.businessAccountId, 'acc-prof-456');
      expect(capturedArgs!.placeName, 'Empório Gourmet');
      expect(capturedArgs!.isApproved, isTrue);
    });

    testWidgets('toque em "Ver perfil público" navega para a página pública do local',
        (tester) async {
      const args = PlaceManagementArgs(
        placeId: 'place-xyz-789',
        businessAccountId: 'acc-def',
        placeName: 'Restaurante Sabor',
        city: 'Belo Horizonte',
        state: 'MG',
        status: 'APPROVED',
      );

      String? capturedPlaceId;

      await tester.pumpWidget(
        MaterialApp(
          onGenerateRoute: (settings) {
            if (settings.name == AppRouter.placeDetail) {
              capturedPlaceId = settings.arguments as String?;
              return MaterialPageRoute(
                builder: (_) => const Scaffold(body: Text('PlaceDetail Mock')),
              );
            }
            return MaterialPageRoute(
              builder: (_) => const PlaceManagementDashboardScreen(args: args),
            );
          },
        ),
      );

      await tester.ensureVisible(find.byKey(const Key('view_public_profile_option')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('view_public_profile_option')));
      await tester.pumpAndSettle();

      expect(capturedPlaceId, 'place-xyz-789');
    });
    testWidgets('toque em "Horários de funcionamento" navega para a tela de edição com argumentos corretos',
        (tester) async {
      const args = PlaceManagementArgs(
        placeId: 'place-hours-1',
        businessAccountId: 'acc-hours-1',
        placeName: 'Café da Esquina',
        city: 'Curitiba',
        state: 'PR',
        status: 'APPROVED',
      );

      PlaceOpeningHoursEditArgs? capturedArgs;

      await tester.pumpWidget(
        MaterialApp(
          onGenerateRoute: (settings) {
            if (settings.name == AppRouter.placeOpeningHoursEdit) {
              capturedArgs = settings.arguments as PlaceOpeningHoursEditArgs?;
              return MaterialPageRoute(
                builder: (_) => const Scaffold(body: Text('Opening Hours Mock')),
              );
            }
            return MaterialPageRoute(
              builder: (_) => const PlaceManagementDashboardScreen(args: args),
            );
          },
        ),
      );

      await tester.tap(find.byKey(const Key('manage_opening_hours_option')));
      await tester.pumpAndSettle();

      expect(capturedArgs, isNotNull);
      expect(capturedArgs!.placeId, 'place-hours-1');
      expect(capturedArgs!.businessAccountId, 'acc-hours-1');
      expect(capturedArgs!.placeName, 'Café da Esquina');
      expect(capturedArgs!.isApproved, isTrue);
    });

    testWidgets('toque em "Comodidades" navega para a tela de edição com argumentos corretos',
        (tester) async {
      const args = PlaceManagementArgs(
        placeId: 'place-amenities-1',
        businessAccountId: 'acc-amenities-1',
        placeName: 'Bistrô Conforto',
        city: 'Florianópolis',
        state: 'SC',
        status: 'APPROVED',
      );

      PlaceAmenitiesEditArgs? capturedArgs;

      await tester.pumpWidget(
        MaterialApp(
          onGenerateRoute: (settings) {
            if (settings.name == AppRouter.placeAmenitiesEdit) {
              capturedArgs = settings.arguments as PlaceAmenitiesEditArgs?;
              return MaterialPageRoute(
                builder: (_) => const Scaffold(body: Text('Amenities Mock')),
              );
            }
            return MaterialPageRoute(
              builder: (_) => const PlaceManagementDashboardScreen(args: args),
            );
          },
        ),
      );

      await tester.tap(find.byKey(const Key('manage_amenities_option')));
      await tester.pumpAndSettle();

      expect(capturedArgs, isNotNull);
      expect(capturedArgs!.placeId, 'place-amenities-1');
      expect(capturedArgs!.businessAccountId, 'acc-amenities-1');
      expect(capturedArgs!.placeName, 'Bistrô Conforto');
      expect(capturedArgs!.isApproved, isTrue);
    });

    testWidgets('toque em "Ofertas e produtos" navega para a tela de gestao com argumentos corretos',
        (tester) async {
      const args = PlaceManagementArgs(
        placeId: 'place-offers-1',
        businessAccountId: 'acc-offers-1',
        placeName: 'Empório Central',
        city: 'Porto Alegre',
        state: 'RS',
        status: 'APPROVED',
      );

      PlaceOffersManagementArgs? capturedArgs;

      await tester.pumpWidget(
        MaterialApp(
          onGenerateRoute: (settings) {
            if (settings.name == AppRouter.placeOffersManagement) {
              capturedArgs = settings.arguments as PlaceOffersManagementArgs?;
              return MaterialPageRoute(
                builder: (_) => const Scaffold(body: Text('Offers Mock')),
              );
            }
            return MaterialPageRoute(
              builder: (_) => const PlaceManagementDashboardScreen(args: args),
            );
          },
        ),
      );

      await tester.ensureVisible(find.byKey(const Key('manage_offers_option')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('manage_offers_option')));
      await tester.pumpAndSettle();

      expect(capturedArgs, isNotNull);
      expect(capturedArgs!.placeId, 'place-offers-1');
      expect(capturedArgs!.businessAccountId, 'acc-offers-1');
      expect(capturedArgs!.placeName, 'Empório Central');
      expect(capturedArgs!.isApproved, isTrue);
    });
  });
}
