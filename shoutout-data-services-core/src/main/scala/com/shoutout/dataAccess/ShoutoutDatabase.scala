package com.shoutout.dataAccess

import com.jolbox.bonecp.{ BoneCPDataSource, BoneCPConfig }
import com.shoutout.ds.DSConfiguration
import scala.slick.session.Database

object ShoutoutDatabase extends DSConfiguration {

  val source = {
    val dsConfig = new BoneCPConfig
    dsConfig.setPoolName("mainPool")
    dsConfig.setJdbcUrl(DBConfiguration.url)
    dsConfig.setUsername(DBConfiguration.user)
    dsConfig.setPassword(DBConfiguration.pass)
    dsConfig.setMinConnectionsPerPartition(DBConfiguration.minConnectionsPerPartition)
    dsConfig.setMaxConnectionsPerPartition(DBConfiguration.maxConnectionsPerPartition)
    dsConfig.setPartitionCount(DBConfiguration.numPartitions)
    new BoneCPDataSource(dsConfig)
  }

  val db = Database.forDataSource(source)

}