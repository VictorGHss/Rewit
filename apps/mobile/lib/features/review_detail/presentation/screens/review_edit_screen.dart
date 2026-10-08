import 'package:flutter/material.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/features/review_creation/presentation/widgets/rating_bar_widget.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/update_review_input.dart';

/// Tela para edição de uma avaliação existente pelo autor (Step 25.3 / C5.7).
///
/// Permite alterar:
/// - Texto da experiência (até 2000 caracteres);
/// - Notas dos alvos existentes (1.0 a 5.0);
/// - Anonimato;
/// - Visibilidade (PUBLIC, FOLLOWERS, PRIVATE).
class ReviewEditScreen extends StatefulWidget {
  final FeedReview review;
  final FeedRepository feedRepository;

  const ReviewEditScreen({
    super.key,
    required this.review,
    required this.feedRepository,
  });

  @override
  State<ReviewEditScreen> createState() => _ReviewEditScreenState();
}

class _ReviewEditScreenState extends State<ReviewEditScreen> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _experienceTextController;

  late Map<String, double> _targetRatings;
  late bool _isAnonymous;
  late String _visibility;

  bool _isSubmitting = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _experienceTextController = TextEditingController(
      text: widget.review.experienceText ?? '',
    );
    _targetRatings = {
      for (final target in widget.review.targets) target.targetId: target.rating,
    };
    _isAnonymous = widget.review.isAnonymous;
    _visibility = widget.review.visibility.toUpperCase();
    if (!['PUBLIC', 'FOLLOWERS', 'PRIVATE'].contains(_visibility)) {
      _visibility = 'PUBLIC';
    }
  }

  @override
  void dispose() {
    _experienceTextController.dispose();
    super.dispose();
  }

  Future<void> _submitEdit() async {
    if (_isSubmitting) return;

    if (!(_formKey.currentState?.validate() ?? false)) {
      return;
    }

    setState(() {
      _isSubmitting = true;
      _errorMessage = null;
    });

    final input = UpdateReviewInput(
      experienceText: _experienceTextController.text.trim(),
      targetRatings: _targetRatings,
      isAnonymous: _isAnonymous,
      visibility: _visibility,
    );

    try {
      final updatedReview = await widget.feedRepository.updateReview(
        widget.review.id,
        input,
      );

      if (mounted) {
        Navigator.of(context).pop(updatedReview);
      }
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _isSubmitting = false;
          _errorMessage = e.detail;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(e.detail),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      }
    } on NetworkException catch (e) {
      if (mounted) {
        setState(() {
          _isSubmitting = false;
          _errorMessage = e.message;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(e.message),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _isSubmitting = false;
          _errorMessage = 'Falha inesperada ao atualizar avaliação. Tente novamente.';
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: const Text('Falha inesperada ao atualizar avaliação. Tente novamente.'),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return PopScope(
      canPop: !_isSubmitting,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Editar Avaliação'),
          actions: [
            if (_isSubmitting)
              const Center(
                child: Padding(
                  padding: EdgeInsets.symmetric(horizontal: 16.0),
                  child: SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                ),
              )
            else
              TextButton(
                key: const Key('save_review_edit_button'),
                onPressed: _submitEdit,
                child: const Text('Salvar'),
              ),
          ],
        ),
        body: Form(
          key: _formKey,
          child: ListView(
            padding: const EdgeInsets.all(16.0),
            children: [
              // 1. Mensagem de erro caso a submissão falhe
              if (_errorMessage != null) ...[
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.errorContainer,
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: theme.colorScheme.error.withAlpha(100)),
                  ),
                  child: Row(
                    children: [
                      Icon(Icons.error_outline, color: theme.colorScheme.error, size: 20),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          _errorMessage!,
                          style: TextStyle(
                            color: theme.colorScheme.onErrorContainer,
                            fontSize: 13,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 16),
              ],

              // 2. Alerta de votos úteis caso existam
              if (widget.review.helpfulCount > 0) ...[
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: Colors.amber.shade50,
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: Colors.amber.shade400),
                  ),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(Icons.info_outline, color: Colors.amber.shade900, size: 20),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          'Esta avaliação já possui ${widget.review.helpfulCount} voto(s) de útil. Caso as notas dos alvos sejam modificadas, a alteração poderá ser recusada pelo servidor.',
                          style: TextStyle(
                            color: Colors.amber.shade900,
                            fontSize: 12,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 16),
              ],

              // 3. Notas dos alvos avaliados
              if (widget.review.targets.isNotEmpty) ...[
                Text(
                  'Notas dos Itens Avaliados',
                  style: theme.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  'Alvos não podem ser adicionados ou removidos após a publicação.',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurface.withAlpha(150),
                  ),
                ),
                const SizedBox(height: 12),
                ...widget.review.targets.map((target) {
                  final currentRating = _targetRatings[target.targetId] ?? target.rating;
                  final typeBadge = target.targetType ?? 'ITEM';

                  return Card(
                    elevation: 0,
                    margin: const EdgeInsets.only(bottom: 12),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                      side: BorderSide(color: Colors.grey.withAlpha(50)),
                    ),
                    child: Padding(
                      padding: const EdgeInsets.all(16.0),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Container(
                                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                                decoration: BoxDecoration(
                                  color: theme.colorScheme.primaryContainer,
                                  borderRadius: BorderRadius.circular(6),
                                ),
                                child: Text(
                                  typeBadge.toUpperCase(),
                                  style: TextStyle(
                                    fontSize: 11,
                                    fontWeight: FontWeight.bold,
                                    color: theme.colorScheme.onPrimaryContainer,
                                  ),
                                ),
                              ),
                              const SizedBox(width: 8),
                              Expanded(
                                child: Text(
                                  'ID: ${target.targetId}',
                                  style: const TextStyle(
                                    fontSize: 12,
                                    fontFamily: 'monospace',
                                    color: Colors.grey,
                                  ),
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 12),
                          RatingBarWidget(
                            rating: currentRating,
                            enabled: !_isSubmitting,
                            onRatingChanged: (newRating) {
                              setState(() {
                                _targetRatings[target.targetId] = newRating;
                              });
                            },
                          ),
                          if (target.specificComment != null &&
                              target.specificComment!.isNotEmpty) ...[
                            const SizedBox(height: 8),
                            Text(
                              'Comentário específico: "${target.specificComment}"',
                              style: TextStyle(
                                fontSize: 12,
                                fontStyle: FontStyle.italic,
                                color: Colors.grey.shade600,
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                  );
                }),
                const SizedBox(height: 16),
              ],

              // 4. Texto da experiência
              Text(
                'Texto da Experiência',
                style: theme.textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 8),
              TextFormField(
                key: const Key('edit_experience_text_field'),
                controller: _experienceTextController,
                maxLines: 5,
                maxLength: 2000,
                enabled: !_isSubmitting,
                decoration: const InputDecoration(
                  hintText: 'Conte mais detalhes sobre sua experiência...',
                  border: OutlineInputBorder(),
                  alignLabelWithHint: true,
                ),
                validator: (value) {
                  if (value != null && value.length > 2000) {
                    return 'O texto não pode exceder 2000 caracteres.';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 16),

              // 5. Visibilidade
              Text(
                'Visibilidade da Publicação',
                style: theme.textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(
                key: const Key('edit_visibility_dropdown'),
                initialValue: _visibility,
                decoration: const InputDecoration(
                  border: OutlineInputBorder(),
                  contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                ),
                items: const [
                  DropdownMenuItem(
                    value: 'PUBLIC',
                    child: Text('Pública (visível a todos)'),
                  ),
                  DropdownMenuItem(
                    value: 'FOLLOWERS',
                    child: Text('Apenas seguidores'),
                  ),
                  DropdownMenuItem(
                    value: 'PRIVATE',
                    child: Text('Privada (apenas você)'),
                  ),
                ],
                onChanged: _isSubmitting
                    ? null
                    : (value) {
                        if (value != null) {
                          setState(() {
                            _visibility = value;
                          });
                        }
                      },
              ),
              const SizedBox(height: 20),

              // 6. Anonimato
              Card(
                elevation: 0,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                  side: BorderSide(color: Colors.grey.withAlpha(50)),
                ),
                child: SwitchListTile(
                  key: const Key('edit_anonymous_switch'),
                  title: const Text('Publicar como anônimo'),
                  subtitle: const Text(
                    'Oculta seu nome e avatar da publicação pública.',
                    style: TextStyle(fontSize: 12),
                  ),
                  value: _isAnonymous,
                  onChanged: _isSubmitting
                      ? null
                      : (val) {
                          setState(() {
                            _isAnonymous = val;
                          });
                        },
                ),
              ),
              const SizedBox(height: 32),

              // 7. Botão principal de salvar
              FilledButton.icon(
                key: const Key('submit_edit_button'),
                onPressed: _isSubmitting ? null : _submitEdit,
                icon: _isSubmitting
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      )
                    : const Icon(Icons.check),
                label: Text(_isSubmitting ? 'Salvando...' : 'Salvar Alterações'),
                style: FilledButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 16),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
