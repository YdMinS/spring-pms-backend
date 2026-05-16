package com.pms.service;

import com.pms.domain.Package;
import com.pms.dto.request.PackageRequest;
import com.pms.dto.response.PackageResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.PackageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.BDDMockito;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PackageServiceTest {

    @Mock
    private PackageRepository packageRepository;

    @InjectMocks
    private PackageServiceImpl packageService;

    private PackageRequest testRequest;

    @BeforeEach
    public void setUp() {
        testRequest = PackageRequest.builder()
                .type("S")
                .cost(new BigDecimal("2.50"))
                .effectiveDate(LocalDate.now())
                .isDefault(false)
                .build();
    }

    // createPackage tests
    @Test
    public void testCreatePackageWithValidRequest() {
        Package newPackage = Package.builder()
                .id(1L)
                .type("S")
                .cost(new BigDecimal("2.50"))
                .effectiveDate(LocalDate.now())
                .isDefault(false)
                .build();
        given(packageRepository.save(any())).willReturn(newPackage);

        PackageResponse response = packageService.createPackage(testRequest);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isNotNull();
        assertThat(response.getType()).isEqualTo("S");
        assertThat(response.getCost()).isEqualByComparingTo(new BigDecimal("2.50"));
        assertThat(response.getIsDefault()).isFalse();
    }

    @Test
    public void testCreatePackageSetIsDefaultTrue() {
        PackageRequest request = PackageRequest.builder()
                .type("M")
                .cost(new BigDecimal("3.50"))
                .effectiveDate(LocalDate.now())
                .isDefault(true)
                .build();

        Package existingDefault = Package.builder()
                .id(1L)
                .type("S")
                .cost(new BigDecimal("2.50"))
                .effectiveDate(LocalDate.now())
                .isDefault(true)
                .build();

        Package newPackage = Package.builder()
                .id(2L)
                .type("M")
                .cost(new BigDecimal("3.50"))
                .effectiveDate(LocalDate.now())
                .isDefault(true)
                .build();

        given(packageRepository.findByIsDefaultTrue()).willReturn(Optional.of(existingDefault));
        given(packageRepository.save(any())).willReturn(newPackage);

        PackageResponse response = packageService.createPackage(request);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(2L);
        assertThat(response.getIsDefault()).isTrue();

        ArgumentCaptor<Package> captor = ArgumentCaptor.forClass(Package.class);
        verify(packageRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getIsDefault()).isFalse();
        assertThat(captor.getAllValues().get(1).getIsDefault()).isTrue();
    }

    @Test
    public void testCreatePackageSetIsDefaultFalse() {
        Package newPackage = Package.builder()
                .id(2L)
                .type("M")
                .cost(new BigDecimal("3.50"))
                .effectiveDate(LocalDate.now())
                .isDefault(false)
                .build();

        given(packageRepository.save(any())).willReturn(newPackage);

        PackageResponse response = packageService.createPackage(testRequest);

        assertThat(response).isNotNull();
        assertThat(response.getIsDefault()).isFalse();

        verify(packageRepository, times(1)).save(any());
        verify(packageRepository, never()).findByIsDefaultTrue();
    }

    // getPackage tests
    @Test
    public void testGetPackageWithValidId() {
        Package pkg = Package.builder()
                .id(1L)
                .type("S")
                .cost(new BigDecimal("2.50"))
                .effectiveDate(LocalDate.now())
                .isDefault(false)
                .build();

        given(packageRepository.findById(1L)).willReturn(Optional.of(pkg));

        PackageResponse response = packageService.getPackage(1L);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getType()).isEqualTo("S");
    }

    @Test
    public void testGetPackageWithInvalidId() {
        given(packageRepository.findById(9999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> packageService.getPackage(9999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // getPackages tests
    @Test
    public void testGetPackagesWithData() {
        List<Package> packages = List.of(
                Package.builder().id(1L).type("S").cost(new BigDecimal("2.50")).effectiveDate(LocalDate.now()).isDefault(true).build(),
                Package.builder().id(2L).type("M").cost(new BigDecimal("3.50")).effectiveDate(LocalDate.now()).isDefault(false).build(),
                Package.builder().id(3L).type("L").cost(new BigDecimal("4.50")).effectiveDate(LocalDate.now()).isDefault(false).build()
        );

        given(packageRepository.findAll()).willReturn(packages);

        List<PackageResponse> responses = packageService.getPackages();

        assertThat(responses).isNotNull();
        assertThat(responses).hasSize(3);
    }

    @Test
    public void testGetPackagesEmpty() {
        given(packageRepository.findAll()).willReturn(List.of());

        List<PackageResponse> responses = packageService.getPackages();

        assertThat(responses).isNotNull();
        assertThat(responses).isEmpty();
    }

    // updatePackage tests
    @Test
    public void testUpdatePackageWithValidRequest() {
        Package existingPackage = Package.builder()
                .id(1L)
                .type("S")
                .cost(new BigDecimal("2.50"))
                .effectiveDate(LocalDate.now())
                .isDefault(false)
                .build();

        PackageRequest updateRequest = PackageRequest.builder()
                .type("M")
                .cost(new BigDecimal("3.50"))
                .effectiveDate(LocalDate.now())
                .isDefault(false)
                .build();

        Package updatedPackage = Package.builder()
                .id(1L)
                .type("M")
                .cost(new BigDecimal("3.50"))
                .effectiveDate(LocalDate.now())
                .isDefault(false)
                .build();

        given(packageRepository.findById(1L)).willReturn(Optional.of(existingPackage));
        given(packageRepository.save(any())).willReturn(updatedPackage);

        PackageResponse response = packageService.updatePackage(1L, updateRequest);

        assertThat(response).isNotNull();
        assertThat(response.getType()).isEqualTo("M");
        assertThat(response.getCost()).isEqualByComparingTo(new BigDecimal("3.50"));
    }

    @Test
    public void testUpdatePackageSetIsDefaultTrue() {
        Package existingPackage = Package.builder()
                .id(1L)
                .type("S")
                .cost(new BigDecimal("2.50"))
                .effectiveDate(LocalDate.now())
                .isDefault(false)
                .build();

        Package currentDefault = Package.builder()
                .id(2L)
                .type("M")
                .cost(new BigDecimal("3.50"))
                .effectiveDate(LocalDate.now())
                .isDefault(true)
                .build();

        PackageRequest updateRequest = PackageRequest.builder()
                .type("S")
                .cost(new BigDecimal("2.50"))
                .effectiveDate(LocalDate.now())
                .isDefault(true)
                .build();

        Package updatedPackage = Package.builder()
                .id(1L)
                .type("S")
                .cost(new BigDecimal("2.50"))
                .effectiveDate(LocalDate.now())
                .isDefault(true)
                .build();

        given(packageRepository.findById(1L)).willReturn(Optional.of(existingPackage));
        given(packageRepository.findByIsDefaultTrue()).willReturn(Optional.of(currentDefault));
        given(packageRepository.save(any())).willReturn(updatedPackage);

        PackageResponse response = packageService.updatePackage(1L, updateRequest);

        assertThat(response).isNotNull();
        assertThat(response.getIsDefault()).isTrue();

        ArgumentCaptor<Package> captor = ArgumentCaptor.forClass(Package.class);
        verify(packageRepository, times(2)).save(captor.capture());
    }

    @Test
    public void testUpdatePackageWithInvalidId() {
        given(packageRepository.findById(9999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> packageService.updatePackage(9999L, testRequest))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // deletePackage tests
    @Test
    public void testDeletePackageWithValidId() {
        Package pkg = Package.builder()
                .id(1L)
                .type("S")
                .cost(new BigDecimal("2.50"))
                .effectiveDate(LocalDate.now())
                .isDefault(false)
                .build();

        given(packageRepository.findById(1L)).willReturn(Optional.of(pkg));
        doNothing().when(packageRepository).delete(any());

        packageService.deletePackage(1L);

        verify(packageRepository, times(1)).delete(any());
    }

    @Test
    public void testDeletePackageWithInvalidId() {
        given(packageRepository.findById(9999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> packageService.deletePackage(9999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
