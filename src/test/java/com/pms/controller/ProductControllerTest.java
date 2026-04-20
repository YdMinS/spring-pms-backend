package com.pms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.Product;
import com.pms.domain.Unit;
import com.pms.dto.request.CreateProductRequest;
import com.pms.dto.request.UpdateProductRequest;
import com.pms.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Phase 4 - Product CRUD API 통합 테스트")
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
    }

    @Test
    @DisplayName("✅ POST /api/products - 이미지 없이 상품 생성")
    void createProductWithoutImage() throws Exception {
        CreateProductRequest request = new CreateProductRequest();
        request.setBarcodeId(1L);
        request.setBrand("Apple");
        request.setProductName("iPhone 15");
        request.setPrice(1299000L);
        request.setStore("Store A");
        request.setUnit(Unit.KG);
        request.setWeight("500g");

        mockMvc.perform(multipart("/api/products")
                .part("product", objectMapper.writeValueAsBytes(request))
                .contentType("application/json"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Product created successfully"))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.barcodeId").value(1))
                .andExpect(jsonPath("$.data.brand").value("Apple"))
                .andExpect(jsonPath("$.data.productName").value("iPhone 15"))
                .andExpect(jsonPath("$.data.price").value(1299000))
                .andExpect(jsonPath("$.data.unit").value("KG"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.imageUrl").isNull());

        // DB에 저장됐는지 확인
        assertThat(productRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("✅ POST /api/products - Unit enum 검증 (잘못된 값)")
    void createProductWithInvalidUnit() throws Exception {
        String invalidRequest = "{\"barcodeId\":2,\"brand\":\"Samsung\",\"productName\":\"Galaxy\",\"price\":999000,\"store\":\"Store B\",\"unit\":\"개\",\"weight\":\"300g\"}";

        mockMvc.perform(multipart("/api/products")
                .part("product", invalidRequest.getBytes())
                .contentType("application/json"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("✅ POST /api/products - Unit 모든 값 테스트 (KG, G, L, ML)")
    void createProductWithDifferentUnits() throws Exception {
        Unit[] units = {Unit.KG, Unit.G, Unit.L, Unit.ML};

        for (int i = 0; i < units.length; i++) {
            CreateProductRequest request = new CreateProductRequest();
            request.setBarcodeId((long) i + 100);
            request.setBrand("Brand" + i);
            request.setProductName("Product" + i);
            request.setPrice(1000L + i);
            request.setStore("Store" + i);
            request.setUnit(units[i]);
            request.setWeight("100" + units[i].getValue());

            mockMvc.perform(multipart("/api/products")
                    .part("product", objectMapper.writeValueAsBytes(request))
                    .contentType("application/json"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.unit").value(units[i].toString()));
        }

        assertThat(productRepository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("✅ GET /api/products - 모든 활성 상품 조회")
    void getAllProducts() throws Exception {
        // 2개의 활성 상품 생성
        for (int i = 0; i < 2; i++) {
            Product product = Product.builder()
                    .barcodeId((long) i)
                    .brand("Brand" + i)
                    .productName("Product" + i)
                    .price(1000L + i)
                    .store("Store" + i)
                    .unit(Unit.KG)
                    .weight("100g")
                    .active(true)
                    .build();
            productRepository.save(product);
        }

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].active").value(true))
                .andExpect(jsonPath("$.data[1].active").value(true));
    }

    @Test
    @DisplayName("✅ GET /api/products/{id} - 단일 상품 조회")
    void getProductById() throws Exception {
        Product product = Product.builder()
                .barcodeId(1L)
                .brand("Apple")
                .productName("iPhone 15")
                .price(1299000L)
                .store("Store A")
                .unit(Unit.KG)
                .weight("500g")
                .active(true)
                .build();
        Product saved = productRepository.save(product);

        mockMvc.perform(get("/api/products/" + saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(saved.getId()))
                .andExpect(jsonPath("$.data.barcodeId").value(1))
                .andExpect(jsonPath("$.data.unit").value("KG"));
    }

    @Test
    @DisplayName("✅ PATCH /api/products/{id} - 부분 업데이트 (가격만)")
    void updateProductPartially() throws Exception {
        Product product = Product.builder()
                .barcodeId(1L)
                .brand("Apple")
                .productName("iPhone 15")
                .price(1299000L)
                .store("Store A")
                .unit(Unit.KG)
                .weight("500g")
                .active(true)
                .build();
        Product saved = productRepository.save(product);

        UpdateProductRequest request = new UpdateProductRequest();
        request.setPrice(1199000L);

        mockMvc.perform(patch("/api/products/" + saved.getId())
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(1199000L))
                .andExpect(jsonPath("$.data.brand").value("Apple")); // 변경 안 됨

        // DB에서 확인
        Product updated = productRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated.getPrice()).isEqualTo(1199000L);
        assertThat(updated.getBrand()).isEqualTo("Apple");
    }

    @Test
    @DisplayName("✅ PATCH /api/products/{id} - Unit 업데이트")
    void updateProductUnit() throws Exception {
        Product product = Product.builder()
                .barcodeId(1L)
                .brand("Coca Cola")
                .productName("Coke")
                .price(3000L)
                .store("Store A")
                .unit(Unit.KG)
                .weight("500ml")
                .active(true)
                .build();
        Product saved = productRepository.save(product);

        UpdateProductRequest request = new UpdateProductRequest();
        request.setUnit(Unit.ML);

        mockMvc.perform(patch("/api/products/" + saved.getId())
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unit").value("ML"));
    }

    @Test
    @DisplayName("✅ DELETE /api/products/{id} - 소프트 삭제 (active=false)")
    void deleteProduct() throws Exception {
        Product product = Product.builder()
                .barcodeId(1L)
                .brand("Apple")
                .productName("iPhone 15")
                .price(1299000L)
                .store("Store A")
                .unit(Unit.KG)
                .weight("500g")
                .active(true)
                .build();
        Product saved = productRepository.save(product);

        mockMvc.perform(delete("/api/products/" + saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Product deleted successfully"));

        // DB에서 확인 - 삭제되지 않고 active=false
        Product deleted = productRepository.findById(saved.getId()).orElseThrow();
        assertThat(deleted.getActive()).isFalse();

        // GET 조회 - 소프트 삭제된 상품은 조회 안 됨
        mockMvc.perform(get("/api/products/" + saved.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("✅ GET /api/products - 소프트 삭제된 상품 제외")
    void getProductsExcludeInactive() throws Exception {
        // 활성 상품 1개, 비활성 상품 1개 생성
        Product active = Product.builder()
                .barcodeId(1L)
                .brand("Active")
                .productName("Active Product")
                .price(1000L)
                .store("Store A")
                .unit(Unit.KG)
                .weight("100g")
                .active(true)
                .build();
        productRepository.save(active);

        Product inactive = Product.builder()
                .barcodeId(2L)
                .brand("Inactive")
                .productName("Inactive Product")
                .price(2000L)
                .store("Store B")
                .unit(Unit.KG)
                .weight("200g")
                .active(false)
                .build();
        productRepository.save(inactive);

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].brand").value("Active"));
    }

    @Test
    @DisplayName("✅ POST /api/products - 필수 필드 검증")
    void createProductValidation() throws Exception {
        CreateProductRequest request = new CreateProductRequest();
        request.setBarcodeId(null); // 필수 필드 누락

        mockMvc.perform(multipart("/api/products")
                .part("product", objectMapper.writeValueAsBytes(request))
                .contentType("application/json"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("✅ ResponseDTO 구조 검증")
    void responseStructureValidation() throws Exception {
        Product product = Product.builder()
                .barcodeId(1L)
                .brand("Test")
                .productName("Test Product")
                .price(1000L)
                .store("Store")
                .unit(Unit.KG)
                .weight("100g")
                .active(true)
                .build();
        productRepository.save(product);

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.timestamp").isString());
    }
}
