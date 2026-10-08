import 'package:image_picker/image_picker.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/selected_media_item.dart';

/// Contrato para seleção de arquivos de mídia (galeria/câmera).
abstract class MediaPickerService {
  /// Seleciona uma ou mais imagens respeitando o limite máximo especificado.
  Future<List<SelectedMediaItem>> pickImages({int maxImages = 5});
}

/// Implementação do serviço de seleção de mídia utilizando a biblioteca `image_picker`.
class ImagePickerMediaService implements MediaPickerService {
  final ImagePicker _picker;

  ImagePickerMediaService({ImagePicker? picker}) : _picker = picker ?? ImagePicker();

  @override
  Future<List<SelectedMediaItem>> pickImages({int maxImages = 5}) async {
    final pickedFiles = await _picker.pickMultiImage(limit: maxImages);
    if (pickedFiles.isEmpty) return [];

    final List<SelectedMediaItem> items = [];
    final limitedFiles = pickedFiles.take(maxImages);

    for (final file in limitedFiles) {
      final bytes = await file.readAsBytes();
      final length = await file.length();
      final name = file.name;
      String mime = file.mimeType ?? '';
      if (mime.isEmpty) {
        if (name.toLowerCase().endsWith('.png')) {
          mime = 'image/png';
        } else {
          mime = 'image/jpeg';
        }
      }

      items.add(
        SelectedMediaItem(
          id: '${DateTime.now().microsecondsSinceEpoch}_${items.length}',
          name: name,
          sizeBytes: length,
          mimeType: mime,
          bytes: bytes,
        ),
      );
    }

    return items;
  }
}
