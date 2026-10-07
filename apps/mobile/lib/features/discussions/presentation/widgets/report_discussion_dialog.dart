import 'package:flutter/material.dart';
import 'package:rewit_mobile/features/discussions/domain/entities/discussion_entities.dart';

/// Diálogo acessível para submissão de denúncia contra um comentário.
class ReportDiscussionDialog extends StatefulWidget {
  final String discussionId;
  final Future<String> Function(ReportReason reason, String? detail) onConfirm;

  const ReportDiscussionDialog({
    super.key,
    required this.discussionId,
    required this.onConfirm,
  });

  static Future<void> show(
    BuildContext context, {
    required String discussionId,
    required Future<String> Function(ReportReason reason, String? detail) onConfirm,
  }) {
    return showDialog(
      context: context,
      builder: (context) => ReportDiscussionDialog(
        discussionId: discussionId,
        onConfirm: onConfirm,
      ),
    );
  }

  @override
  State<ReportDiscussionDialog> createState() => _ReportDiscussionDialogState();
}

class _ReportDiscussionDialogState extends State<ReportDiscussionDialog> {
  ReportReason _selectedReason = ReportReason.inappropriateContent;
  final TextEditingController _detailController = TextEditingController();
  bool _isSubmitting = false;
  String? _errorMessage;

  @override
  void dispose() {
    _detailController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() {
      _isSubmitting = true;
      _errorMessage = null;
    });

    try {
      final message = await widget.onConfirm(
        _selectedReason,
        _detailController.text.trim().isNotEmpty ? _detailController.text.trim() : null,
      );

      if (mounted) {
        Navigator.of(context).pop();
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(message),
            backgroundColor: Colors.green.shade700,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _isSubmitting = false;
          _errorMessage = 'Falha ao enviar denúncia. Tente novamente mais tarde.';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Denunciar Comentário'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Ajude a manter o Rewit seguro selecionando o motivo que melhor descreve esta ocorrência:',
              style: TextStyle(fontSize: 13),
            ),
            const SizedBox(height: 12),
            if (_errorMessage != null) ...[
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: Colors.red.shade50,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Text(
                  _errorMessage!,
                  style: TextStyle(color: Colors.red.shade800, fontSize: 12),
                ),
              ),
              const SizedBox(height: 12),
            ],
            RadioGroup<ReportReason>(
              groupValue: _selectedReason,
              onChanged: (value) {
                if (!_isSubmitting && value != null) {
                  setState(() => _selectedReason = value);
                }
              },
              child: Column(
                children: ReportReason.values.map((reason) {
                  return RadioListTile<ReportReason>(
                    value: reason,
                    title: Text(reason.label, style: const TextStyle(fontSize: 13)),
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                  );
                }).toList(),
              ),
            ),


            const SizedBox(height: 12),
            TextField(
              controller: _detailController,
              enabled: !_isSubmitting,
              maxLength: 500,
              maxLines: 2,
              decoration: const InputDecoration(
                labelText: 'Detalhes adicionais (opcional)',
                hintText: 'Explique brevemente o motivo da denúncia...',
                border: OutlineInputBorder(),
                isDense: true,
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _isSubmitting ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancelar'),
        ),
        FilledButton(
          onPressed: _isSubmitting ? null : _submit,
          child: _isSubmitting
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                )
              : const Text('Enviar denúncia'),
        ),
      ],
    );
  }
}
