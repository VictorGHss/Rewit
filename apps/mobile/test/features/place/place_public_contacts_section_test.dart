import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/services/url_launcher_service.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/presentation/widgets/place_public_contacts_section.dart';

class _FakeUrlLauncherService implements UrlLauncherService {
  String? lastLaunchedWebUrl;
  String? lastLaunchedPhone;

  LaunchResult webResultToReturn = LaunchResult.success;
  LaunchResult phoneResultToReturn = LaunchResult.success;

  @override
  Future<LaunchResult> launchWebUrl(String url) async {
    lastLaunchedWebUrl = url;
    return webResultToReturn;
  }

  @override
  Future<LaunchResult> launchPhone(String phone) async {
    lastLaunchedPhone = phone;
    return phoneResultToReturn;
  }
}

void main() {
  group('PlacePublicContactsSection Widget Tests (F2.5)', () {
    late _FakeUrlLauncherService fakeLauncher;

    setUp(() {
      fakeLauncher = _FakeUrlLauncherService();
    });

    PlaceDetail createPlace({
      String? phone,
      String? websiteUrl,
      String? instagramUrl,
      String? facebookUrl,
    }) {
      return PlaceDetail(
        id: 'place-1',
        name: 'Restaurante Sabor',
        slug: 'restaurante-sabor',
        category: 'ALIMENTACAO',
        description: 'Restaurante acolhedor',
        addressText: 'Rua das Flores, 123',
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

    Widget createWidget(PlaceDetail place) {
      return MaterialApp(
        home: Scaffold(
          body: PlacePublicContactsSection(
            place: place,
            urlLauncherService: fakeLauncher,
          ),
        ),
      );
    }

    testWidgets('exibe os 4 contatos quando todos estão preenchidos', (tester) async {
      final place = createPlace(
        phone: '+55 (11) 98765-4321',
        websiteUrl: 'https://restaurantesabor.com.br',
        instagramUrl: 'https://instagram.com/restaurantesabor',
        facebookUrl: 'https://facebook.com/restaurantesabor',
      );

      await tester.pumpWidget(createWidget(place));

      expect(find.byKey(const Key('place_public_contacts_section')), findsOneWidget);
      expect(find.text('Contatos e Redes Sociais'), findsOneWidget);

      expect(find.byKey(const Key('place_contact_phone_item')), findsOneWidget);
      expect(find.text('+55 (11) 98765-4321'), findsOneWidget);

      expect(find.byKey(const Key('place_contact_website_item')), findsOneWidget);
      expect(find.text('https://restaurantesabor.com.br'), findsOneWidget);

      expect(find.byKey(const Key('place_contact_instagram_item')), findsOneWidget);
      expect(find.text('https://instagram.com/restaurantesabor'), findsOneWidget);

      expect(find.byKey(const Key('place_contact_facebook_item')), findsOneWidget);
      expect(find.text('https://facebook.com/restaurantesabor'), findsOneWidget);
    });

    testWidgets('não exibe seção quando todos os contatos são nulos ou vazios', (tester) async {
      final place = createPlace(
        phone: null,
        websiteUrl: '',
        instagramUrl: '   ',
        facebookUrl: null,
      );

      await tester.pumpWidget(createWidget(place));

      expect(find.byKey(const Key('place_public_contacts_section')), findsNothing);
      expect(find.text('Contatos e Redes Sociais'), findsNothing);
    });

    testWidgets('exibe apenas contatos preenchidos, ocultando ausentes sem linhas vazias', (tester) async {
      final place = createPlace(
        phone: '+55 11 91234-5678',
        websiteUrl: null,
        instagramUrl: 'https://instagram.com/sabor',
        facebookUrl: '',
      );

      await tester.pumpWidget(createWidget(place));

      expect(find.byKey(const Key('place_public_contacts_section')), findsOneWidget);
      expect(find.byKey(const Key('place_contact_phone_item')), findsOneWidget);
      expect(find.byKey(const Key('place_contact_instagram_item')), findsOneWidget);

      expect(find.byKey(const Key('place_contact_website_item')), findsNothing);
      expect(find.byKey(const Key('place_contact_facebook_item')), findsNothing);
    });

    testWidgets('toque no telefone invoca launchPhone e não gera erro em caso de sucesso', (tester) async {
      final place = createPlace(phone: '+55 (11) 98765-4321');
      await tester.pumpWidget(createWidget(place));

      await tester.tap(find.byKey(const Key('place_contact_phone_item')));
      await tester.pumpAndSettle();

      expect(fakeLauncher.lastLaunchedPhone, '+55 (11) 98765-4321');
      expect(find.byType(SnackBar), findsNothing);
    });

    testWidgets('toque no telefone com falha de inicialização apresenta feedback compreensível', (tester) async {
      fakeLauncher.phoneResultToReturn = LaunchResult.launchFailed;
      final place = createPlace(phone: '11999998888');
      await tester.pumpWidget(createWidget(place));

      await tester.tap(find.byKey(const Key('place_contact_phone_item')));
      await tester.pumpAndSettle();

      expect(find.text('Não foi possível iniciar a chamada telefônica no dispositivo.'), findsOneWidget);
    });

    testWidgets('toque no site oficial invoca launchWebUrl', (tester) async {
      final place = createPlace(websiteUrl: 'https://meusite.com');
      await tester.pumpWidget(createWidget(place));

      await tester.tap(find.byKey(const Key('place_contact_website_item')));
      await tester.pumpAndSettle();

      expect(fakeLauncher.lastLaunchedWebUrl, 'https://meusite.com');
      expect(find.byType(SnackBar), findsNothing);
    });

    testWidgets('link web com esquema proibido apresenta feedback de esquema', (tester) async {
      fakeLauncher.webResultToReturn = LaunchResult.invalidScheme;
      final place = createPlace(websiteUrl: 'ftp://arquivo.com');
      await tester.pumpWidget(createWidget(place));

      await tester.tap(find.byKey(const Key('place_contact_website_item')));
      await tester.pumpAndSettle();

      expect(find.text('Endereço inválido: apenas links http ou https são permitidos.'), findsOneWidget);
    });

    testWidgets('falha ao abrir link no navegador apresenta feedback adequado sem quebrar a tela', (tester) async {
      fakeLauncher.webResultToReturn = LaunchResult.launchFailed;
      final place = createPlace(instagramUrl: 'https://instagram.com/perfil');
      await tester.pumpWidget(createWidget(place));

      await tester.tap(find.byKey(const Key('place_contact_instagram_item')));
      await tester.pumpAndSettle();

      expect(find.text('Não foi possível abrir o link no navegador.'), findsOneWidget);
    });

    testWidgets('endereços longos não estouram o layout na interface', (tester) async {
      final place = createPlace(
        websiteUrl: 'https://site-muito-longo-com-subdominio-especifico.com.br/departamento/subsecao/pagina-promocional-especial-de-verao-2026',
      );
      await tester.pumpWidget(createWidget(place));

      expect(tester.takeException(), isNull);
      expect(find.byKey(const Key('place_contact_website_item')), findsOneWidget);
    });
  });
}
