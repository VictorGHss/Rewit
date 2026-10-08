import 'package:flutter/material.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/media_picker_service.dart';
import 'package:rewit_mobile/features/review_detail/domain/repositories/review_media_repository.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';
import '../../domain/entities/review_creation_input.dart';
import '../../domain/repositories/review_creation_repository.dart';
import '../state/review_create_notifier.dart';
import '../state/review_create_state.dart';
import '../widgets/review_media_picker_section.dart';
import '../widgets/target_item_input_widget.dart';

/// Tela completa de criação de publicações de avaliação multi-alvo (Step 11.0 / Step 25.3 / C4 Mídia).
class ReviewCreateScreen extends StatefulWidget {
  final ReviewCreationRepository? repository;
  final ReviewMediaRepository? mediaRepository;
  final MediaPickerService? mediaPickerService;
  final SearchRepository? searchRepository;
  final ReviewCreateNotifier? notifier;
  final ValueChanged<FeedReview>? onReviewCreated;
  final String? initialTargetId;
  final String? initialTargetName;
  final String? initialTargetType;
  final String? initialCategory;

  const ReviewCreateScreen({
    super.key,
    this.repository,
    this.mediaRepository,
    this.mediaPickerService,
    this.searchRepository,
    this.notifier,
    this.onReviewCreated,
    this.initialTargetId,
    this.initialTargetName,
    this.initialTargetType,
    this.initialCategory,
  });

  @override
  State<ReviewCreateScreen> createState() => _ReviewCreateScreenState();
}

class _ReviewCreateScreenState extends State<ReviewCreateScreen> {
  late final ReviewCreateNotifier _notifier;
  bool _ownsNotifier = false;

  final TextEditingController _contextPlaceIdController = TextEditingController();
  final TextEditingController _experienceTextController = TextEditingController();
  final TextEditingController _latitudeController = TextEditingController();
  final TextEditingController _longitudeController = TextEditingController();
  final TextEditingController _accuracyController = TextEditingController();

  @override
  void initState() {
    super.initState();
    if (widget.notifier != null) {
      _notifier = widget.notifier!;
    } else {
      _notifier = ReviewCreateNotifier(
        repository: widget.repository ?? _FallbackReviewCreationRepository(),
        mediaRepository: widget.mediaRepository,
        mediaPickerService: widget.mediaPickerService,
        initialTargetId: widget.initialTargetId,
        initialTargetName: widget.initialTargetName,
        initialTargetType: widget.initialTargetType,
        initialCategory: widget.initialCategory,
      );
      _ownsNotifier = true;
    }

    if (_notifier.contextPlaceId != null && _notifier.contextPlaceId!.isNotEmpty) {
      _contextPlaceIdController.text = _notifier.contextPlaceId!;
    }

    _notifier.addListener(_handleStateChange);
  }

  void _handleStateChange() {
    if (!mounted) return;
    if (_contextPlaceIdController.text != (_notifier.contextPlaceId ?? '')) {
      _contextPlaceIdController.text = _notifier.contextPlaceId ?? '';
    }
    final state = _notifier.state;
    if (state is ReviewCreateSuccess) {
      if (state.hasMediaFailures) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              'Avaliação criada com sucesso! Porém ${state.failedMediaCount} anexo(s) falharam no upload.',
            ),
            backgroundColor: Colors.orange.shade800,
            duration: const Duration(seconds: 5),
          ),
        );
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              state.uploadedMediaCount > 0
                  ? 'Avaliação publicada com ${state.uploadedMediaCount} foto(s) anexada(s)!'
                  : 'Avaliação publicada com sucesso!',
            ),
            backgroundColor: Colors.green,
          ),
        );
      }
      widget.onReviewCreated?.call(state.createdReview);
    }
  }

  @override
  void dispose() {
    _notifier.removeListener(_handleStateChange);
    if (_ownsNotifier) {
      _notifier.dispose();
    }
    _contextPlaceIdController.dispose();
    _experienceTextController.dispose();
    _latitudeController.dispose();
    _longitudeController.dispose();
    _accuracyController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    FocusScope.of(context).unfocus();
    await _notifier.submit();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Criar Avaliação'),
      ),
      body: ListenableBuilder(
        listenable: _notifier,
        builder: (context, _) {
          final state = _notifier.state;
          final isSubmitting = state.isSubmitting;

          return SingleChildScrollView(
            padding: const EdgeInsets.all(16.0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // Banner de Erro RFC 7807 / Validação Local
                if (state is ReviewCreateError) ...[
                  Card(
                    color: theme.colorScheme.errorContainer,
                    elevation: 0,
                    margin: const EdgeInsets.only(bottom: 16),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                      side: BorderSide(color: theme.colorScheme.error.withAlpha(80)),
                    ),
                    child: Padding(
                      padding: const EdgeInsets.all(14.0),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Icon(
                            Icons.error_outline,
                            color: theme.colorScheme.error,
                            size: 24,
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Text(
                                      state.isRateLimited
                                          ? 'Limite Excedido (429)'
                                          : 'Falha na Validação',
                                      style: TextStyle(
                                        fontWeight: FontWeight.bold,
                                        fontSize: 14,
                                        color: theme.colorScheme.onErrorContainer,
                                      ),
                                    ),
                                    if (state.errorCode != null) ...[
                                      const SizedBox(width: 6),
                                      Container(
                                        padding: const EdgeInsets.symmetric(
                                          horizontal: 6,
                                          vertical: 2,
                                        ),
                                        decoration: BoxDecoration(
                                          color: theme.colorScheme.error.withAlpha(30),
                                          borderRadius: BorderRadius.circular(4),
                                        ),
                                        child: Text(
                                          state.errorCode!,
                                          style: TextStyle(
                                            fontSize: 10,
                                            fontWeight: FontWeight.bold,
                                            color: theme.colorScheme.error,
                                          ),
                                        ),
                                      ),
                                    ],
                                  ],
                                ),
                                const SizedBox(height: 4),
                                Text(
                                  state.message,
                                  style: TextStyle(
                                    fontSize: 13,
                                    color: theme.colorScheme.onErrorContainer,
                                  ),
                                ),
                                if (state.hasFieldErrors) ...[
                                  const SizedBox(height: 6),
                                  ...state.fieldErrors.entries.map(
                                    (e) => Padding(
                                      padding: const EdgeInsets.only(top: 2.0),
                                      child: Text(
                                        '• ${e.key}: ${e.value}',
                                        style: TextStyle(
                                          fontSize: 12,
                                          fontWeight: FontWeight.w500,
                                          color: theme.colorScheme.onErrorContainer,
                                        ),
                                      ),
                                    ),
                                  ),
                                ],
                                if (state.retryAfterSeconds != null) ...[
                                  const SizedBox(height: 4),
                                  Text(
                                    'Por favor, aguarde ${state.retryAfterSeconds} segundos antes de tentar novamente.',
                                    style: TextStyle(
                                      fontSize: 12,
                                      fontWeight: FontWeight.w600,
                                      color: theme.colorScheme.error,
                                    ),
                                  ),
                                ],
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],

                // Seção: Alvos Avaliados (Multi-alvo)
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      'Alvos da Avaliação *',
                      style: theme.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    Text(
                      '${_notifier.targets.length} alvo(s)',
                      style: TextStyle(
                        fontSize: 12,
                        color: theme.colorScheme.onSurface.withAlpha(150),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 4),
                Text(
                  'Avalie o estabelecimento e/ou itens e serviços específicos.',
                  style: TextStyle(
                    fontSize: 12,
                    color: theme.colorScheme.onSurface.withAlpha(160),
                  ),
                ),
                const SizedBox(height: 12),

                ...List.generate(_notifier.targets.length, (index) {
                  final target = _notifier.targets[index];
                  return TargetItemInputWidget(
                    key: ValueKey('target_item_$index'),
                    index: index,
                    target: target,
                    canRemove: _notifier.targets.length > 1,
                    enabled: !isSubmitting,
                    searchRepository: widget.searchRepository,
                    isTargetSelectedElsewhere: (targetId) =>
                        _notifier.isTargetAlreadySelected(targetId, excludeIndex: index),
                    onTargetSelected: (item) {
                      _notifier.selectTarget(index, item);
                    },
                    onClearSelection: () {
                      _notifier.clearTargetSelection(index);
                    },
                    onTargetIdChanged: (val) {
                      _notifier.updateTarget(index, targetId: val);
                    },
                    onRatingChanged: (val) {
                      _notifier.updateTarget(index, rating: val);
                    },
                    onSpecificCommentChanged: (val) {
                      _notifier.updateTarget(index, specificComment: val);
                    },
                    onRemove: () {
                      _notifier.removeTarget(index);
                    },
                  );
                }),

                OutlinedButton.icon(
                  onPressed: isSubmitting
                      ? null
                      : () {
                          _notifier.addTarget();
                        },
                  icon: const Icon(Icons.add, size: 18),
                  label: const Text('Adicionar Outro Alvo / Item'),
                  style: OutlinedButton.styleFrom(
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(8),
                    ),
                  ),
                ),
                const SizedBox(height: 20),

                // Seção: Estabelecimento de Contexto
                Text(
                  'Local de Contexto (Opcional)',
                  style: theme.textTheme.titleSmall?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 6),
                TextFormField(
                  controller: _contextPlaceIdController,
                  enabled: !isSubmitting,
                  decoration: const InputDecoration(
                    labelText: 'Identificador do Local (UUID)',
                    hintText: 'ex: 00000000-0000-0000-0000-000000000002',
                    prefixIcon: Icon(Icons.place_outlined, size: 20),
                    isDense: true,
                    border: OutlineInputBorder(),
                  ),
                  onChanged: (val) {
                    _notifier.setContextPlaceId(val);
                  },
                ),
                const SizedBox(height: 20),

                // Seção: Relato Geral da Experiência
                Text(
                  'Relato da Experiência (Opcional)',
                  style: theme.textTheme.titleSmall?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 6),
                TextFormField(
                  controller: _experienceTextController,
                  enabled: !isSubmitting,
                  maxLines: 4,
                  maxLength: 2000,
                  decoration: const InputDecoration(
                    labelText: 'Conte sua experiência geral...',
                    hintText: 'O que achou do ambiente, tempo de espera, custo-benefício?',
                    border: OutlineInputBorder(),
                    alignLabelWithHint: true,
                  ),
                  onChanged: (val) {
                    _notifier.setExperienceText(val);
                  },
                ),
                const SizedBox(height: 12),

                // Seção: Visibilidade e Privacidade
                Text(
                  'Visibilidade e Privacidade',
                  style: theme.textTheme.titleSmall?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 8),

                DropdownButtonFormField<String>(
                  initialValue: _notifier.visibility,
                  decoration: const InputDecoration(
                    labelText: 'Quem pode ver sua avaliação',
                    prefixIcon: Icon(Icons.visibility_outlined, size: 20),
                    isDense: true,
                    border: OutlineInputBorder(),
                  ),
                  items: const [
                    DropdownMenuItem(
                      value: 'PUBLIC',
                      child: Text('Público (visível a toda a comunidade)'),
                    ),
                    DropdownMenuItem(
                      value: 'FOLLOWERS',
                      child: Text('Seguidores (apenas quem te segue)'),
                    ),
                    DropdownMenuItem(
                      value: 'PRIVATE',
                      child: Text('Privado (apenas você)'),
                    ),
                  ],
                  onChanged: isSubmitting
                      ? null
                      : (val) {
                          if (val != null) {
                            _notifier.setVisibility(val);
                          }
                        },
                ),
                const SizedBox(height: 12),

                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  value: _notifier.isAnonymous,
                  onChanged: isSubmitting
                      ? null
                      : (val) {
                          _notifier.setAnonymous(val);
                        },
                  title: const Text('Publicar como Anônimo'),
                  subtitle: const Text(
                    'Seu nome e perfil não serão exibidos publicamente na avaliação.',
                    style: TextStyle(fontSize: 12),
                  ),
                ),
                const SizedBox(height: 12),

                // Seção: Presença Geográfica (Opcional)
                ExpansionTile(
                  tilePadding: EdgeInsets.zero,
                  leading: const Icon(Icons.my_location_outlined),
                  title: const Text('Presença e Check-in no Local (Opcional)'),
                  subtitle: const Text(
                    'Informar coordenadas para validação de presença no local',
                    style: TextStyle(fontSize: 12),
                  ),
                  children: [
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 8.0),
                      child: Column(
                        children: [
                          Row(
                            children: [
                              Expanded(
                                child: TextFormField(
                                  controller: _latitudeController,
                                  enabled: !isSubmitting,
                                  keyboardType: const TextInputType.numberWithOptions(
                                    decimal: true,
                                    signed: true,
                                  ),
                                  decoration: const InputDecoration(
                                    labelText: 'Latitude (-90 a 90)',
                                    isDense: true,
                                    border: OutlineInputBorder(),
                                  ),
                                  onChanged: (val) {
                                    final lat = double.tryParse(val);
                                    _notifier.setCoordinates(
                                      latitude: lat,
                                      longitude: _notifier.userLongitude,
                                      accuracyMeters: _notifier.locationAccuracyMeters,
                                    );
                                  },
                                ),
                              ),
                              const SizedBox(width: 8),
                              Expanded(
                                child: TextFormField(
                                  controller: _longitudeController,
                                  enabled: !isSubmitting,
                                  keyboardType: const TextInputType.numberWithOptions(
                                    decimal: true,
                                    signed: true,
                                  ),
                                  decoration: const InputDecoration(
                                    labelText: 'Longitude (-180 a 180)',
                                    isDense: true,
                                    border: OutlineInputBorder(),
                                  ),
                                  onChanged: (val) {
                                    final lng = double.tryParse(val);
                                    _notifier.setCoordinates(
                                      latitude: _notifier.userLatitude,
                                      longitude: lng,
                                      accuracyMeters: _notifier.locationAccuracyMeters,
                                    );
                                  },
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 8),
                          TextFormField(
                            controller: _accuracyController,
                            enabled: !isSubmitting,
                            keyboardType: const TextInputType.numberWithOptions(decimal: true),
                            decoration: const InputDecoration(
                              labelText: 'Precisão em metros (opcional)',
                              isDense: true,
                              border: OutlineInputBorder(),
                            ),
                            onChanged: (val) {
                              final acc = double.tryParse(val);
                              _notifier.setCoordinates(
                                latitude: _notifier.userLatitude,
                                longitude: _notifier.userLongitude,
                                accuracyMeters: acc,
                              );
                            },
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 16),

                // Seção de Seleção e Upload de Fotos/Mídias
                ReviewMediaPickerSection(
                  notifier: _notifier,
                  enabled: !isSubmitting,
                ),
                const SizedBox(height: 12),

                // Botão de Submissão com proteção anti-duplo clique
                SizedBox(
                  height: 50,
                  child: ElevatedButton(
                    onPressed: isSubmitting ? null : _submit,
                    style: ElevatedButton.styleFrom(
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                    ),
                    child: isSubmitting
                        ? const SizedBox(
                            width: 22,
                            height: 22,
                            child: CircularProgressIndicator(
                              strokeWidth: 2.5,
                              color: Colors.white,
                            ),
                          )
                        : const Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Icon(Icons.send_rounded, size: 20),
                              SizedBox(width: 8),
                              Text(
                                'Publicar Avaliação',
                                style: TextStyle(
                                  fontSize: 16,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ],
                          ),
                  ),
                ),
                const SizedBox(height: 24),
              ],
            ),
          );
        },
      ),
    );
  }
}

class _FallbackReviewCreationRepository implements ReviewCreationRepository {
  @override
  Future<FeedReview> createReview(CreateReviewInput input) async {
    throw UnimplementedError('Nenhum repositório de criação de avaliações fornecido.');
  }
}
