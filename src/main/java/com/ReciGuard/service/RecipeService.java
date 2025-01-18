package com.ReciGuard.service;

import com.ReciGuard.dto.*;
import com.ReciGuard.entity.*;
import com.ReciGuard.repository.*;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RecipeService {

    private final RecipeRepository recipeRepository;
    private final IngredientRepository ingredientRepository;
    private final UserService userService;
    private final RecipeStatsRepository recipeStatsRepository;
    private final RecipeIngredientRepository recipeIngredientRepository;
    private final UserScrapRepository userScrapRepository;
    private final UserIngredientRepository userIngredientRepository;
    private final InstructionRepository instructionRepository;
    private final S3Uploader s3Uploader;
    private final RestTemplate restTemplate;

    @Value("http://localhost:3000/recommend") // 나중에 ai 모델 완성 후 수정
    private String recipeRecommendApiUrl;

    @Value("http://localhost:8000/check_allergy")
    private String similarAllergyApiUrl;

    public RecipeRecommendResponseDTO getTodayRecipe(Long userId) {
        // AI 모델에 전달할 데이터 준비
        Map<String, Object> requestPayload = Map.of("user_id", userId);

        try {
            // AI 모델 API 호출
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    recipeRecommendApiUrl,
                    HttpMethod.POST,
                    new HttpEntity<>(requestPayload),
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );

            log.info("AI 모델 응답: {}", response.getBody());
            log.info("Calling AI model with userId: {}", userId);

            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null || !responseBody.containsKey("recipe_id")) {
                log.error("AI 모델 응답에 recipeId가 없습니다.");
                return new RecipeRecommendResponseDTO(null, null, null);
            }

            // AI 모델로부터 받은 recipeId
            Double recipeIdDouble = Double.valueOf(responseBody.get("recipe_id").toString());
            Long recipeId = recipeIdDouble.longValue();

            // recipeId에 해당하는 레시피 정보 조회
            Recipe recipe = recipeRepository.findById(recipeId)
                        .orElseThrow(() -> new RuntimeException("레시피를 찾을 수 없습니다: " + recipeId));

            // 추천 레시피 반환
            return new RecipeRecommendResponseDTO(
                                        recipe.getId(),
                                        recipe.getImagePath(),
                                        recipe.getRecipeName());

            } catch (HttpClientErrorException e){
                log.error("AI 모델 API 호출 실패: {}", e.getStatusCode());
                log.info("Calling AI model with userId: {}", userId);
                return new RecipeRecommendResponseDTO(null, null, null);
            } catch (Exception e) {
                log.error("AI 모델 호출 중 오류 발생: {}", e.getMessage());
                log.info("Calling AI model with userId: {}", userId);
                return new RecipeRecommendResponseDTO(null, null, null);
        }
    }

    // 전체 레시피 리스트
    public List<RecipeListResponseDTO> getAllRecipes(Long userId) {

        List<Recipe> recipes = recipeRepository.findAll();

        if (recipes.isEmpty()) {
            throw new EntityNotFoundException("레시피를 찾을 수 없습니다.");
        }

        return recipes.stream()
                .map(recipe -> {
                    boolean isScrapped = userScrapRepository.existsUserScrap(userId, recipe.getId());
                    return new RecipeListResponseDTO(
                            recipe.getId(),
                            recipe.getRecipeName(),
                            recipe.getImagePath(),
                            recipe.getServing(),
                            isScrapped // 추가
                    );
                })
                .collect(Collectors.toList());
    }

    // 전체 레시피 리스트 -> 필터링 후
    public List<RecipeListResponseDTO> getAllFilteredRecipes(Long userId) {

        List<String> allergyIngredients = userIngredientRepository.findAllergyIngredientsByUserId(userId);

        if (allergyIngredients.isEmpty()) {
            throw new EntityNotFoundException("사용자의 알레르기 정보가 없습니다.");
        }

        // 사용자 알레르기 정보를 제외한 레시피 필터링
        List<Recipe> recipes = recipeRepository.findAllFilteredRecipes(userId, allergyIngredients);

        if (recipes.isEmpty()) {
            throw new EntityNotFoundException("조건에 맞는 레시피가 없습니다.");
        }

        // DTO 변환 및 반환
        return recipes.stream()
                .map(recipe -> {
                    boolean scrapped = userScrapRepository.existsUserScrap(userId, recipe.getId());
                    return new RecipeListResponseDTO(
                            recipe.getId(),
                            recipe.getRecipeName(),
                            recipe.getImagePath(),
                            recipe.getServing(),
                            scrapped
                    );
                })
                .collect(Collectors.toList());
    }

    // cuisine 별 레시피 리스트
    public List<RecipeListResponseDTO> getRecipesByCuisine(Long userId, String cuisine){
        List<Recipe> recipes = recipeRepository.findByCuisine(cuisine);

        if (recipes.isEmpty()) {
            throw new EntityNotFoundException("검색 결과가 없습니다.");
        }

        return recipes.stream()
                .map(recipe -> {
                    boolean scrapped = userScrapRepository.existsUserScrap(userId, recipe.getId());
                    return new RecipeListResponseDTO(
                            recipe.getId(),
                            recipe.getRecipeName(),
                            recipe.getImagePath(),
                            recipe.getServing(),
                            scrapped
                    );
                })
                .collect(Collectors.toList());
    }

    // cuisine 별 레시피 리스트 -> 필터링 후
    public List<RecipeListResponseDTO> getFilteredRecipesByCuisine(Long userId, String cuisine) {

        List<String> allergyIngredients = userIngredientRepository.findAllergyIngredientsByUserId(userId);

        if (allergyIngredients.isEmpty()) {
            throw new EntityNotFoundException("사용자의 알레르기 정보가 없습니다.");
        }

        // 사용자 알레르기 정보를 제외한 레시피 필터링
        List<Recipe> recipes = recipeRepository.findCuisineFilteredRecipes(userId, cuisine, allergyIngredients);

        if (recipes.isEmpty()) {
            throw new EntityNotFoundException("사용자 알레르기 정보를 바탕으로 " + cuisine + "에 해당하는 레시피가 없습니다.");
        }

        // DTO 변환 및 반환
        return recipes.stream()
                .map(recipe -> {
                    boolean scrapped = userScrapRepository.existsUserScrap(userId, recipe.getId());
                    return new RecipeListResponseDTO(
                            recipe.getId(),
                            recipe.getRecipeName(),
                            recipe.getImagePath(),
                            recipe.getServing(),
                            scrapped
                    );
                })
                .collect(Collectors.toList());
    }

    // query 검색에 따른 레시피 리스트
    public List<RecipeListResponseDTO> getRecipesByQuery(Long userId, String query){
        List<Recipe> recipes = recipeRepository.findByQuery(query);

        if (recipes.isEmpty()) {
            throw new EntityNotFoundException(query + "로(으로) 검색된 결과가 없습니다.");
        }

        return recipes.stream()
                .map(recipe -> {
                    boolean scrapped = userScrapRepository.existsUserScrap(userId, recipe.getId());
                    return new RecipeListResponseDTO(
                            recipe.getId(),
                            recipe.getRecipeName(),
                            recipe.getImagePath(),
                            recipe.getServing(),
                            scrapped
                    );
                })
                .collect(Collectors.toList());
    }

    // 검색 단어와 사용자 알레르기 정보를 기반으로 필터링된 레시피 리스트 검색
    public List<RecipeListResponseDTO> getFilteredRecipesByQuery(Long userId, String query) {

        List<String> allergyIngredients = userIngredientRepository.findAllergyIngredientsByUserId(userId);

        if (allergyIngredients.isEmpty()) {
            throw new EntityNotFoundException("사용자의 알레르기 정보가 없습니다.");
        }

        // 사용자 알레르기 정보를 제외한 레시피 필터링
        List<Recipe> recipes = recipeRepository.findQueryFilteredRecipes(userId, query, allergyIngredients);

        if (recipes.isEmpty()) {
            throw new EntityNotFoundException("사용자 알레르기 정보를 바탕으로 " + query + "로 검색된 레시피가 없습니다.");
        }

        // DTO 변환 및 반환
        return recipes.stream()
                .map(recipe -> {
                    boolean scrapped = userScrapRepository.existsUserScrap(userId, recipe.getId());
                    return new RecipeListResponseDTO(
                            recipe.getId(),
                            recipe.getRecipeName(),
                            recipe.getImagePath(),
                            recipe.getServing(),
                            scrapped
                    );
                })
                .collect(Collectors.toList());
    }

    // 알레르기 유발 가능한 유사 재료 받아오는 ai 모델
    private SimilarAllergyIngredientDTO getSimilarAllergyIngredients(Long recipeId, Long userId) {
        try {
            // 요청 페이로드 생성
            Map<String, Object> requestPayload = Map.of(
                    "recipe_id", recipeId,
                    "user_id", userId
            );

            // AI 모델 API 호출
            ResponseEntity<SimilarAllergyIngredientDTO> response = restTemplate.exchange(
                    similarAllergyApiUrl,
                    HttpMethod.POST,
                    new HttpEntity<>(requestPayload),
                    SimilarAllergyIngredientDTO.class
            );
            log.info("AI 모델 응답: {}", response.getBody());
            log.info("Calling AI model with recipeId: {} and userId: {}", recipeId, userId);

            // 응답 본문 반환 (null 체크)
            return response.getBody() != null ? response.getBody() : new SimilarAllergyIngredientDTO(Collections.emptyList());
        } catch (Exception e) {
            log.error("AI 모델 호출 실패: {}", e.getMessage());
            log.info("Calling AI model with recipeId: {} and userId: {}", recipeId, userId);

            return new SimilarAllergyIngredientDTO(Collections.emptyList());
        }
    }

    // 레시피 상세 검색
    public RecipeDetailResponseDTO getRecipeDetail(Long recipeId, Long userId) {

        // 2. 기본 Recipe 정보 로드
        Recipe recipe = recipeRepository.findRecipeById(recipeId)
                .orElseThrow(() -> new EntityNotFoundException("요청한 데이터를 찾을 수 없습니다."));

        // 3. RecipeStats 로드 (viewCount와 scrapCount)
        RecipeStats stats = recipeStatsRepository.findByRecipeId(recipeId)
                .orElseThrow(() -> new EntityNotFoundException("RecipeStats 데이터를 찾을 수 없습니다."));

        // 4. Instructions와 RecipeIngredients 로드
        List<Instruction> instructions = recipeRepository.findInstructionsByRecipeId(recipeId);
        List<RecipeIngredient> recipeIngredients = recipeRepository.findRecipeIngredientsByRecipeId(recipeId);

        // 5. Nutrition 정보 가져오기 (null 가능)
        Nutrition nutrition = recipe.getNutrition();

        // 6. Ingredients 변환
        List<IngredientResponseDTO> ingredients = recipeIngredients.stream()
                .map(recipeIngredient -> new IngredientResponseDTO(
                        recipeIngredient.getIngredient().getIngredient(),
                        recipeIngredient.getQuantity()
                ))
                .collect(Collectors.toList());

        // 7. Instructions 변환
        List<InstructionResponseDTO> instructionDTOs = instructions.stream()
                .map(instruction -> new InstructionResponseDTO(
                        instruction.getInstructionImage(),
                        instruction.getInstruction()
                ))
                .collect(Collectors.toList());

        // 8. AI 모델에서 유사 알레르기 유발 재료 가져오기
        SimilarAllergyIngredientDTO similarAllergyIngredientsDTO = getSimilarAllergyIngredients(recipe.getId(), userId);
        List<String> similarAllergyIngredients = similarAllergyIngredientsDTO.getSimilarIngredient();

        // isScrapped 값 확인
        boolean scrapped = userScrapRepository.existsUserScrap(userId, recipe.getId());

        // 9. RecipeDetailResponseDTO 생성 및 반환
        return new RecipeDetailResponseDTO(
                recipe.getImagePath(),
                recipe.getRecipeName(),
                recipe.getServing(),
                recipe.getCuisine(),
                recipe.getFoodType(),
                recipe.getCookingStyle(),
                nutrition != null ? (int) nutrition.getCalories() : 0,
                nutrition != null ? (int) nutrition.getSodium() : 0,
                nutrition != null ? (int) nutrition.getCarbohydrate() : 0,
                nutrition != null ? (int) nutrition.getFat() : 0,
                nutrition != null ? (int) nutrition.getProtein() : 0,
                scrapped,
                stats != null ? stats.getScrapCount() : 0,
                stats != null ? stats.getViewCount() : 0,
                ingredients,
                instructionDTOs,
                similarAllergyIngredients
        );
    }

    @Transactional
    public Recipe saveMyRecipe(MyRecipeForm recipeForm, MultipartFile recipeImage, Map<String, MultipartFile> instructionImageFiles, HttpServletRequest request) {

        // 1. 현재 인증된 사용자의 username 가져오기
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        Long userId = userService.findUserIdByUsername(username);

        // 2. Recipe 엔티티 생성 및 설정
        Recipe recipe = new Recipe();

        recipe.setRecipeName(recipeForm.getRecipeName());
        // 레시피 사진 업로드 (사용자가 업로드한 경우만!!)
        if (recipeImage != null && !recipeImage.isEmpty()) {
            String uploadedRecipeImage = s3Uploader.saveFile(recipeImage);
            recipe.setImagePath(uploadedRecipeImage); // 단일 사진 저장
        } else {
            recipe.setImagePath(null); // 사진이 없으면 null 처리
        }

        recipe.setServing(recipeForm.getServing());
        recipe.setCuisine(recipeForm.getCuisine());
        recipe.setFoodType(recipeForm.getFoodType());
        recipe.setCookingStyle(recipeForm.getCookingStyle());

        // 3. userId로 User 엔티티 연관관계 설정
        User user = new User();
        user.setUserid(userId);
        recipe.setUser(user);

        // 4. Ingredients 저장
        List<RecipeIngredient> ingredients = recipeForm.getIngredients().stream()
                .map(ingredientDto -> {
                    RecipeIngredient recipeIngredient = new RecipeIngredient();

                    // Ingredient 조회
                    Ingredient ingredient = ingredientRepository.findByIngredient(ingredientDto.getIngredient());
                    if (ingredient == null) {
                        // 재료가 없으면 새로 추가
                        ingredient = new Ingredient();
                        ingredient.setIngredient(ingredientDto.getIngredient());
                        ingredient = ingredientRepository.save(ingredient);
                    }
                    // RecipeIngredient 설정
                    recipeIngredient.setIngredient(ingredient);
                    recipeIngredient.setQuantity(ingredientDto.getQuantity());
                    recipeIngredient.setRecipe(recipe);
                    return recipeIngredient;
                })
                .collect(Collectors.toList());
        recipe.setRecipeIngredients(ingredients);

        // 5. Instructions 저장
        if (request instanceof MultipartHttpServletRequest) {
            MultipartHttpServletRequest multipartRequest = (MultipartHttpServletRequest) request;
            Map<String, MultipartFile> parsedInstructionFiles = new HashMap<>();
            multipartRequest.getFileMap().forEach((key, value) -> {
                if (key.startsWith("instructionImageFiles[")) {
                    parsedInstructionFiles.put(key, value);
                }
            });
            instructionImageFiles = parsedInstructionFiles;
        }

        // 파싱된 파일 키 확인
        log.info("Parsed file keys: {}", instructionImageFiles != null ? instructionImageFiles.keySet() : "No files provided");

        // recipe_id 범위 내에서 instruction_id 시작값 설정
        AtomicInteger instructionCounter = new AtomicInteger(
                instructionRepository.findMaxInstructionIdByRecipeId(recipe.getId()).orElse(0) + 1
        );

        // 여기서부터 찐 Instructions 저장!!
        Map<String, MultipartFile> finalInstructionImageFiles = instructionImageFiles;
        List<Instruction> instructions = recipeForm.getInstructions().stream()
                .map(instructionDto -> {
                    Instruction instruction = new Instruction();
                    instruction.setInstruction(instructionDto.getInstruction());

                    // instruction_id 설정
                    instruction.setInstructionId(instructionCounter.getAndIncrement());

                    // 현재 처리 중인 Instruction ID 확인
                    log.info("Processing instruction ID: {}", instruction.getInstructionId());

                    // instructionImageFiles로부터 파일을 매핑하거나 null 처리
                    if (finalInstructionImageFiles != null) {
                        String matchingKey = finalInstructionImageFiles.keySet().stream()
                                .filter(key -> key.equals("instructionImageFiles[" + instruction.getInstructionId() + "]"))
                                .findFirst()
                                .orElse(null);

                        if (matchingKey != null) {
                            log.info("Found matching key for instruction {}: {}", instruction.getInstructionId(), matchingKey);
                            MultipartFile file = finalInstructionImageFiles.get(matchingKey);

                            // 파일이 있으면 업로드, 없으면 null로 처리
                            if (file != null && !file.isEmpty()) {
                                try {
                                    String uploadedUrl = s3Uploader.saveFile(file); // S3에 단일 파일 저장
                                    instruction.setInstructionImage(uploadedUrl); // URL 설정
                                    log.info("Uploaded image for instruction {}: {}", instruction.getInstructionId(), uploadedUrl);
                                } catch (Exception e) {
                                    log.error("Failed to upload image for instruction {}: {}", instruction.getInstructionId(), e.getMessage());
                                    instruction.setInstructionImage(null); // 업로드 실패 시 null
                                }
                            } else {
                                log.info("No file provided for instruction {}", instruction.getInstructionId());
                                instruction.setInstructionImage(null); // 사진이 없으면 null로 처리
                            }
                        } else {
                            log.info("No matching file found for instruction {}", instruction.getInstructionId());
                            instruction.setInstructionImage(null); // 사진이 없으면 null로 처리
                        }
                    } else {
                        log.info("No instruction image files provided");
                        instruction.setInstructionImage(null); // 사진이 없으면 null로 처리
                    }
                    instruction.setRecipe(recipe);

                    return instruction;
                })
                .collect(Collectors.toList());
        recipe.setInstructions(instructions);
        recipeRepository.save(recipe);


        // 6. RecipeStats 기본값 생성
        RecipeStats stats = new RecipeStats();
        stats.setScrapCount(0);
        stats.setViewCount(0);
        stats.setRecipe(recipe);
        recipe.setRecipeStats(stats);

        // 7. Recipe 저장
        return recipeRepository.save(recipe);
    }

    public List<RecipeListResponseDTO> findMyRecipes(Long userId) { // 리스트로 반환 (간단 조회)
        // 해당 사용자의 ID로 등록한 레시피 조회 및 DTO로 변환
        return recipeRepository.findAllByUserId(userId).stream()
                .map(recipe -> {
                    boolean scrapped = userScrapRepository.existsUserScrap(userId, recipe.getId());
                    return new RecipeListResponseDTO(
                            recipe.getId(),
                            recipe.getRecipeName(),
                            recipe.getImagePath(),
                            recipe.getServing(),
                            scrapped
                    );
                })
                .collect(Collectors.toList());
    }

    // 수정 폼 데이터 반환
    public MyRecipeFormEdit getRecipeFormEdit(Long recipeId, Long userId) {
        // 2. recipeId로 레시피 조회 (소유권 검증 포함)
        Recipe recipe = recipeRepository.findRecipeByUserId(recipeId, userId)
                .orElseThrow(() -> new IllegalArgumentException("권한이 없습니다."));

        // 3. MyRecipeForm 생성 및 데이터 매핑
        MyRecipeFormEdit form = new MyRecipeFormEdit();
        form.setRecipeName(recipe.getRecipeName());
        form.setImagePath(recipe.getImagePath());
        form.setServing(recipe.getServing());
        form.setCuisine(recipe.getCuisine());
        form.setFoodType(recipe.getFoodType());
        form.setCookingStyle(recipe.getCookingStyle());

        // 4. 재료 정보 매핑
        List<IngredientResponseDTO> ingredientDTOs = recipe.getRecipeIngredients().stream()
                .map(ingredient -> new IngredientResponseDTO(
                        ingredient.getIngredient().getIngredient(),
                        ingredient.getQuantity()
                ))
                .collect(Collectors.toList());
        form.setIngredients(ingredientDTOs);

        // 5. 조리 단계 정보 매핑
        List<InstructionResponseDTO> instructionDTOs = recipe.getInstructions().stream()
                .map(instruction -> new InstructionResponseDTO(
                        instruction.getInstruction(),
                        instruction.getInstructionImage()
                ))
                .collect(Collectors.toList());
        form.setInstructions(instructionDTOs);

        return form;
    }

    @Transactional
    public RecipeDetailResponseDTO updateMyRecipe(Long recipeId, Long userId, MyRecipeForm recipeForm) {

        // 1. 레시피 ID로 영속 상태의 Recipe 엔티티 조회
        Recipe recipe = recipeRepository.findById(recipeId)
                .orElseThrow(() -> new IllegalArgumentException("레시피를 찾을 수 없습니다."));

        // 2. 레시피 정보 수정
        recipe.setRecipeName(recipeForm.getRecipeName());
        recipe.setServing(recipeForm.getServing());
        recipe.setCuisine(recipeForm.getCuisine());
        recipe.setFoodType(recipeForm.getFoodType());
        recipe.setCookingStyle(recipeForm.getCookingStyle());

        // 3. 재료 수정
        if (recipeForm.getIngredients() != null) {
            List<RecipeIngredient> existingIngredients = recipe.getRecipeIngredients();

            // 업데이트 또는 삭제를 위한 매핑
            Map<String, String> formIngredientMap = recipeForm.getIngredients().stream()
                    .filter(ingredientDto -> {
                        // 재료명과 수량 둘 다 공백인 경우 삭제로 간주 (필터 제외)
                        if (ingredientDto.getIngredient().isBlank() && ingredientDto.getQuantity().isBlank()) {
                            return false; // 제외
                        }
                        // 재료명이나 수량 둘 중 하나라도 공백인 경우 예외 발생
                        if (ingredientDto.getIngredient().isBlank() || ingredientDto.getQuantity().isBlank()) {
                            throw new IllegalArgumentException("재료와 수량을 확인해주세요.");
                        }
                        return true; // 유지
                    })
                    .collect(Collectors.toMap(
                            IngredientResponseDTO::getIngredient,
                            IngredientResponseDTO::getQuantity
                    ));

            // 삭제할 재료를 명시적으로 관리
            List<RecipeIngredient> ingredientsToRemove = new ArrayList<>();
            for (RecipeIngredient existingIngredient : existingIngredients) {
                String formQuantity = formIngredientMap.get(existingIngredient.getIngredient().getIngredient());

                if (formQuantity == null) {
                    // 폼에 없는 데이터는 삭제 대상에 추가
                    ingredientsToRemove.add(existingIngredient);
                } else if (!existingIngredient.getQuantity().equals(formQuantity)) {
                    // quantity가 다르면 업데이트
                    existingIngredient.setQuantity(formQuantity);
                }
            }

            // 1-1. 삭제 대상 제거 (Hibernate에게 명확히 알림)
            ingredientsToRemove.forEach(existingIngredients::remove);
            recipeIngredientRepository.deleteAll(ingredientsToRemove);

            // 2. 추가 처리: 폼에만 있는 데이터 추가
            recipeForm.getIngredients().forEach(ingredientDto -> {
                boolean isExisting = existingIngredients.stream()
                        .anyMatch(existing -> existing.getIngredient().getIngredient().equals(ingredientDto.getIngredient()));

                if (!isExisting) {
                    Ingredient ingredient = ingredientRepository.findByIngredient(ingredientDto.getIngredient());

                    if (ingredient == null) {
                        // 새로운 재료가 DB에 없으면 추가
                        ingredient = new Ingredient();
                        ingredient.setIngredient(ingredientDto.getIngredient());
                        ingredient = ingredientRepository.save(ingredient);
                    }

                    // 새로 추가
                    RecipeIngredient newIngredient = new RecipeIngredient();
                    newIngredient.setIngredient(ingredient);
                    newIngredient.setQuantity(ingredientDto.getQuantity());
                    newIngredient.setRecipe(recipe);
                    existingIngredients.add(newIngredient);
                }
            });
        }

        // 4. 조리 과정 수정
        if (recipeForm.getInstructions() != null) {
            List<Instruction> existingInstructions = recipe.getInstructions();

            // 기존 데이터 매핑 (instruction_id 기준으로)
            Map<Integer, Instruction> existingInstructionMap = existingInstructions.stream()
                    .collect(Collectors.toMap(
                            Instruction::getInstructionId, // key: instruction_id
                            instruction -> instruction // value: Instruction 객체
                    ));

            // 입력된 순서대로 처리
            AtomicInteger currentInstructionId = new AtomicInteger(1);
            List<Instruction> updatedInstructions = new ArrayList<>();

            recipeForm.getInstructions().forEach(instructionDto -> {
                Instruction instruction = existingInstructionMap.get(currentInstructionId.get());

                if (instruction == null) {
                    // 새 데이터 추가
                    instruction = new Instruction();
                    instruction.setInstructionId(currentInstructionId.get());
                    instruction.setRecipe(recipe);
                }

                // 공통 처리: 조리 내용 설정
                instruction.setInstruction(instructionDto.getInstruction());

                // 이미지 처리
                if (instructionDto.hasInstructionImage()) {
                    // 새 이미지 업로드 및 기존 이미지 삭제
                    String uploadedImageUrl = s3Uploader.saveFile(instructionDto.getInstructionImageFile());
                    if (instruction.getInstructionImage() != null) {
                        s3Uploader.deleteFile(instruction.getInstructionImage());
                    }
                    instruction.setInstructionImage(uploadedImageUrl);
                } else if (instructionDto.isImageRemoved()) {
                    // 이미지 삭제 처리
                    if (instruction.getInstructionImage() != null) {
                        s3Uploader.deleteFile(instruction.getInstructionImage());
                    }
                    instruction.setInstructionImage(null);
                }

                updatedInstructions.add(instruction);
                currentInstructionId.incrementAndGet();
            });

            // 기존 데이터 중 폼에 없는 데이터는 삭제 처리
            existingInstructions.removeIf(existing ->
                    updatedInstructions.stream().noneMatch(updated -> updated.getInstructionId() == existing.getInstructionId())
            );

            // 기존 리스트를 업데이트된 리스트로 교체
            existingInstructions.clear();
            existingInstructions.addAll(updatedInstructions);
        }


        // 5. 수정된 재료로 AI 모델 호출
        SimilarAllergyIngredientDTO similarAllergyIngredientsDTO = getSimilarAllergyIngredients(recipe.getId(), userId);
        List<String> similarAllergyIngredients = similarAllergyIngredientsDTO.getSimilarIngredient();

        // 스크랩 여부 확인
        boolean isScrapped = userScrapRepository.existsUserScrap(userId, recipeId);

        // 6. 수정된 레시피를 RecipeDetailResponseDTO로 변환 & 리턴
        return new RecipeDetailResponseDTO(
                recipe.getImagePath(),
                recipe.getRecipeName(),
                recipe.getServing(),
                recipe.getCuisine(),
                recipe.getFoodType(),
                recipe.getCookingStyle(),

                // 사용자가 작성한 레시피에 대해서는 nutrition 정보 제공 x -> 다 0으로 기본 세팅
                recipe.getNutrition() != null ? (int) recipe.getNutrition().getCalories() : 0,
                recipe.getNutrition() != null ? (int) recipe.getNutrition().getSodium() : 0,
                recipe.getNutrition() != null ? (int) recipe.getNutrition().getCarbohydrate() : 0,
                recipe.getNutrition() != null ? (int) recipe.getNutrition().getFat() : 0,
                recipe.getNutrition() != null ? (int) recipe.getNutrition().getProtein() : 0,

                isScrapped,
                recipe.getRecipeStats().getViewCount(), // 조회 수
                recipe.getRecipeStats().getScrapCount(), // 스크랩 수

                recipe.getRecipeIngredients() != null ? recipe.getRecipeIngredients().stream()
                        .map(ingredient -> new IngredientResponseDTO(
                                ingredient.getIngredient().getIngredient(),
                                ingredient.getQuantity()
                        ))
                        .collect(Collectors.toList()) : Collections.emptyList(),
                recipe.getInstructions() != null ? recipe.getInstructions().stream()
                        .map(instruction -> new InstructionResponseDTO(
                                instruction.getInstruction(),
                                instruction.getInstructionImage()
                        ))
                        .collect(Collectors.toList()) : Collections.emptyList(),

                similarAllergyIngredients
        );
    }
}
