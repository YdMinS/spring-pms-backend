package com.pms.service.barcode;

import com.pms.dto.response.BarcodeScanResponse;
import com.pms.exception.InvalidImageException;
import com.pms.service.ImageValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link BarcodeScanServiceImpl}: found / not found (normal result, both null) / unopenable image (400)
 * / validator rejection stops before decoding.
 */
@ExtendWith(MockitoExtension.class)
class BarcodeScanServiceTest {

    @Mock private ImageValidator imageValidator;
    @Mock private BarcodeImageDecoder barcodeImageDecoder;

    @InjectMocks private BarcodeScanServiceImpl service;

    private final MockMultipartFile file =
            new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1, 2, 3});

    @Test
    void scan_barcodeFound_returnsValueAndFormat() {
        given(barcodeImageDecoder.decode(any())).willReturn(Optional.of(new DecodedBarcode("8801234567893", "EAN_13")));

        BarcodeScanResponse result = service.scan(file);

        assertThat(result.barcode()).isEqualTo("8801234567893");
        assertThat(result.format()).isEqualTo("EAN_13");
        verify(imageValidator).validate(file);
    }

    @Test
    void scan_noBarcode_returnsBothNull() {
        given(barcodeImageDecoder.decode(any())).willReturn(Optional.empty());

        BarcodeScanResponse result = service.scan(file);

        assertThat(result.barcode()).isNull();
        assertThat(result.format()).isNull();
    }

    @Test
    void scan_unopenableImage_throwsIllegalArgument() {
        given(barcodeImageDecoder.decode(any())).willThrow(new IllegalArgumentException("이미지를 열 수 없습니다"));

        assertThatThrownBy(() -> service.scan(file)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scan_validatorRejects_doesNotDecode() {
        willThrow(new InvalidImageException("File is empty")).given(imageValidator).validate(file);

        assertThatThrownBy(() -> service.scan(file)).isInstanceOf(InvalidImageException.class);
        verify(barcodeImageDecoder, never()).decode(any());
    }
}
