package com.ReciGuard.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter @Setter
public class RecipeListResponseDTO {
    private String imagePath;
    private String recipeName;
    private int serving;
}
