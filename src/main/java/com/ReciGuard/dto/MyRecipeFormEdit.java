package com.ReciGuard.dto;

import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Getter @Setter
public class MyRecipeFormEdit {
    private String recipeName;                       // 레시피 이름
    private String imagePath;                        // 이미지 경로(메인 이미지)
    private int serving;                             // 인분 수
    private String cuisine;                          // 요리 종류 (예: 한식, 양식 등)
    private String foodType;                         // 음식 타입 (예: 밥, 면, 빵 등)
    private String cookingStyle;                     // 조리 방식 (예: 볶음, 찜 등)
    private boolean isImageRemoved; // 이미지 삭제 여부
    private MultipartFile newImageFile; // 업로드된 새 이미지 파일


    private List<IngredientResponseDTO> ingredients; // 재료 정보
    private List<InstructionRequestDTO> instructions; // 조리 단계 정보


    public boolean hasNewImage() {
        return newImageFile != null && !newImageFile.isEmpty();
    }
}
