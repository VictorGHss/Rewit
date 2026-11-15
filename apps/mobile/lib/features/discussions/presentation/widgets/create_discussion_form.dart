import 'package:flutter/material.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';

/// Formulário acessível e reutilizável para criação de comentário raiz ou resposta.
class CreateDiscussionForm extends StatefulWidget {
  final String? replyingToAuthorName;
  final String? replyingToParentId;
  final VoidCallback? onCancelReply;
  final Future<void> Function(String content, String? parentId) onSubmit;

  const CreateDiscussionForm({
    super.key,
    this.replyingToAuthorName,
    this.replyingToParentId,
    this.onCancelReply,
    required this.onSubmit,
  });

  @override
  State<CreateDiscussionForm> createState() => _CreateDiscussionFormState();
}

class _CreateDiscussionFormState extends State<CreateDiscussionForm> {
  final TextEditingController _controller = TextEditingController();
  bool _isSubmitting = false;
  String? _errorMessage;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _handleSend() async {
    final text = _controller.text.trim();
    if (text.isEmpty) return;

    setState(() {
      _isSubmitting = true;
      _errorMessage = null;
    });

    try {
      await widget.onSubmit(text, widget.replyingToParentId);
      if (mounted) {
        _controller.clear();
        setState(() {
          _isSubmitting = false;
        });
        if (widget.onCancelReply != null) {
          widget.onCancelReply!();
        }
      }
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _isSubmitting = false;
          _errorMessage = e.detail;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _isSubmitting = false;
          _errorMessage = 'Falha ao enviar comentário. Tente novamente.';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final isReplying = widget.replyingToParentId != null;

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: theme.cardTheme.color ?? scheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: scheme.outlineVariant),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Banner indicando resposta a um comentário
          if (isReplying) ...[
            Container(
              margin: const EdgeInsets.only(bottom: 8),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              decoration: BoxDecoration(
                color: scheme.secondaryContainer,
                borderRadius: BorderRadius.circular(6),
              ),
              child: Row(
                children: [
                  Icon(Icons.reply, size: 14, color: scheme.primary),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      'Respondendo a ${widget.replyingToAuthorName ?? "comentário"}',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w500,
                        color: scheme.onSecondaryContainer,
                      ),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  InkWell(
                    onTap: _isSubmitting ? null : widget.onCancelReply,
                    child: const Icon(Icons.close, size: 16),
                  ),
                ],
              ),
            ),
          ],

          // Mensagem de erro RFC 7807 se houver
          if (_errorMessage != null) ...[
            Container(
              margin: const EdgeInsets.only(bottom: 8),
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: scheme.errorContainer,
                borderRadius: BorderRadius.circular(6),
                border: Border.all(color: scheme.error.withAlpha(80)),
              ),
              child: Row(
                children: [
                  Icon(Icons.error_outline, size: 16, color: scheme.error),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      _errorMessage!,
                      style: TextStyle(
                        color: scheme.onErrorContainer,
                        fontSize: 12,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],

          // Campo de texto e botão de envio
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(
                child: TextField(
                  controller: _controller,
                  enabled: !_isSubmitting,
                  maxLength: 2000,
                  maxLines: 3,
                  minLines: 1,
                  decoration: InputDecoration(
                    hintText: isReplying
                        ? 'Escreva sua resposta...'
                        : 'Participe da conversa e comente...',
                    isDense: true,
                    contentPadding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 10,
                    ),
                    border: const OutlineInputBorder(),
                    counterText: '',
                  ),
                ),
              ),
              const SizedBox(width: 8),
              IconButton.filled(
                onPressed: _isSubmitting ? null : _handleSend,
                tooltip:
                    isReplying ? 'Enviar resposta' : 'Publicar comentário',
                icon: _isSubmitting
                    ? SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: scheme.onSurfaceVariant,
                        ),
                      )
                    : const Icon(Icons.send, size: 18),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
