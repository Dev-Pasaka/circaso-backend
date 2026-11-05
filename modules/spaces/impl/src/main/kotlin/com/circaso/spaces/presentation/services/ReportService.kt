package com.circaso.spaces.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.spaces.api.PostId
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.models.MuteDuration
import com.circaso.spaces.domain.models.OpsReportView
import com.circaso.spaces.domain.models.Page
import com.circaso.spaces.domain.models.Report
import com.circaso.spaces.domain.models.ReportAction
import com.circaso.spaces.domain.models.ReportCommand
import com.circaso.spaces.domain.models.ReportGroupView
import com.circaso.spaces.domain.usecases.ListOpsReports
import com.circaso.spaces.domain.usecases.ListReports
import com.circaso.spaces.domain.usecases.RemoveSpace
import com.circaso.spaces.domain.usecases.ReportContent
import com.circaso.spaces.domain.usecases.ResolveReport

/** Reports, for the space's team (SP35-SP37) and Circaso (SPO1, SPO2). */
class ReportService(
    private val reportContent: ReportContent,
    private val listReports: ListReports,
    private val resolveReport: ResolveReport,
    private val listOpsReports: ListOpsReports,
    private val removeSpace: RemoveSpace,
) {
    suspend fun report(command: ReportCommand): Resource<Report> = reportContent(command)

    suspend fun list(caller: UserId, spaceId: SpaceId, open: Boolean): Resource<List<ReportGroupView>> = listReports(caller, spaceId, open)

    suspend fun resolve(
        caller: UserId,
        spaceId: SpaceId,
        postId: PostId,
        action: ReportAction,
        muteDuration: MuteDuration?,
    ): Resource<Unit> = resolveReport(caller, spaceId, postId, action, muteDuration)

    suspend fun opsList(offset: Int, limit: Int): Resource<Page<OpsReportView>> = listOpsReports(offset, limit)

    suspend fun removeSpace(spaceId: SpaceId, reason: String): Resource<Unit> = removeSpace.invoke(spaceId, reason)
}
