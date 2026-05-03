package com.example.smarttourism.data.model

import com.example.smarttourism.data.remote.dto.RouteRequest
import com.example.smarttourism.data.remote.dto.RouteResponse

data class SavedRouteSnapshot(
    val request: RouteRequest,
    val response: RouteResponse
)
